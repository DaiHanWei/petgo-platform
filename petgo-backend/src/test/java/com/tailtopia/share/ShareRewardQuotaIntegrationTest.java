package com.tailtopia.share;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.share.repository.ShareRewardQuotaRepository;
import com.tailtopia.share.service.ShareRewardService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：分享奖励的月度额度总控（Story 18.1）—— 真 PostgreSQL 跑真原子 UPDATE。
 *
 * <p>🛡 并发那条用真线程池打真数据库，不用 mock：这条 AC 要防的正是
 * 「应用层读改写导致的丢更新」，而 mock 里根本不存在行锁，测了等于没测。
 */
class ShareRewardQuotaIntegrationTest extends ApiIntegrationTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    @Autowired
    private ShareRewardService service;

    @Autowired
    private ShareRewardQuotaRepository quotas;

    @Autowired
    private PawCoinConfigRepository configs;

    @Autowired
    private PawCoinWalletService wallet;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private Long savedCap;
    private Boolean savedEnabled;

    private void configure(boolean enabled, long cap) {
        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        if (savedCap == null) {
            savedCap = c.getShareRewardMonthlyCap();
            savedEnabled = c.isShareRewardEnabled();
        }
        c.setShareRewardEnabled(enabled);
        c.setShareRewardMonthlyCap(cap);
        configs.saveAndFlush(c);
    }

    /** 🛡 单行配置表是全局共享的 —— 不还原会污染同一次 run 里的其它测试类。 */
    @AfterEach
    void restoreConfig() {
        if (savedCap == null) {
            return;
        }
        PawCoinConfig c = configs.findById(PawCoinConfig.SINGLETON_ID).orElseThrow();
        c.setShareRewardEnabled(savedEnabled);
        c.setShareRewardMonthlyCap(savedCap);
        configs.saveAndFlush(c);
        savedCap = null;
    }

    private String key() {
        return "share-reward-test-" + SEQ.incrementAndGet();
    }

    // ── AC4：WIB 月界 ────────────────────────────────────────────

    /**
     * 🔴 月界按 WIB 挂钟判定，不是 UTC。
     *
     * <p>钉的是这件事：WIB 8 月 31 日 23:59 与 9 月 1 日 00:01 属于**不同** period。
     * 按 UTC 切的话前者是 8-31T16:59Z、后者是 8-31T17:01Z —— 同一个 UTC 月，
     * 于是月初那天会带着上个月的余额继续算，或者反过来在月末提前重置。
     */
    @Test
    void monthBoundaryFollowsWibWallClockNotUtc() {
        Instant lastMinuteOfAugust = ZonedDateTime.of(2026, 8, 31, 23, 59, 0, 0, WIB).toInstant();
        Instant firstMinuteOfSeptember = ZonedDateTime.of(2026, 9, 1, 0, 1, 0, 0, WIB).toInstant();

        assertThat(ShareRewardService.periodOf(lastMinuteOfAugust)).isEqualTo("2026-08");
        assertThat(ShareRewardService.periodOf(firstMinuteOfSeptember)).isEqualTo("2026-09");
        // 两者在 UTC 下是同一个月 —— 这正是不能按 UTC 切的原因。
        assertThat(lastMinuteOfAugust.atZone(ZoneId.of("UTC")).getMonth())
                .isEqualTo(firstMinuteOfSeptember.atZone(ZoneId.of("UTC")).getMonth());
    }

    /** 月末打满额度，跨到 WIB 次月月初立刻又有额度（惰性重置，无需定时任务）。 */
    @Test
    void quotaResetsAtWibMonthStart() {
        User u = newUser();
        configure(true, 100);
        Instant monthEnd = ZonedDateTime.of(2026, 8, 31, 23, 59, 0, 0, WIB).toInstant();
        Instant monthStart = ZonedDateTime.of(2026, 9, 1, 0, 1, 0, 0, WIB).toInstant();

        assertThat(service.tryReward(u.getId(), 100, "TEST", null, key(), monthEnd)).isTrue();
        assertThat(service.tryReward(u.getId(), 1, "TEST", null, key(), monthEnd))
                .as("同一 WIB 月内已打满").isFalse();

        assertThat(service.tryReward(u.getId(), 100, "TEST", null, key(), monthStart))
                .as("跨到 WIB 次月应重新有额度").isTrue();
    }

    // ── 🛡 AC3：达上限后不发币、但操作本身成功 ──────────────────────

    @Test
    void atCapNothingIsGrantedAndBalanceDoesNotMove() {
        User u = newUser();
        configure(true, 50);
        Instant at = Instant.now();

        assertThat(service.tryReward(u.getId(), 50, "TEST", null, key(), at)).isTrue();
        long balanceAtCap = wallet.balanceOf(u.getId());

        assertThat(service.tryReward(u.getId(), 10, "TEST", null, key(), at)).isFalse();
        assertThat(wallet.balanceOf(u.getId())).as("达上限后不该再入账").isEqualTo(balanceAtCap);
        assertThat(service.grantedThisMonth(u.getId())).isEqualTo(50);
    }

    /**
     * ⚠️ 条件是 {@code granted + coins <= cap} 而不是 {@code granted < cap}。
     *
     * <p>只判「还没满」的话，最后一次发放会冲过上限最多 N-1 枚 ——
     * 上限就成了「上限 + 一次发放量」，而没人会发现。
     */
    @Test
    void aGrantThatWouldExceedTheCapIsRefusedEntirelyNotPartially() {
        User u = newUser();
        configure(true, 100);
        Instant at = Instant.now();

        assertThat(service.tryReward(u.getId(), 90, "TEST", null, key(), at)).isTrue();
        assertThat(service.tryReward(u.getId(), 20, "TEST", null, key(), at))
                .as("90 + 20 > 100，应整笔拒绝而不是发 10").isFalse();
        assertThat(service.grantedThisMonth(u.getId())).isEqualTo(90);
        // 剩下的 10 仍然可以发 —— 拒绝的是那一笔，不是把账号锁死。
        assertThat(service.tryReward(u.getId(), 10, "TEST", null, key(), at)).isTrue();
        assertThat(service.grantedThisMonth(u.getId())).isEqualTo(100);
    }

    // ── 🛡 AC6：总开关 ──────────────────────────────────────────

    @Test
    void masterSwitchOffGrantsNothingRegardlessOfRemainingQuota() {
        User u = newUser();
        configure(false, 100000); // 额度充裕，但开关关着
        Instant at = Instant.now();

        assertThat(service.tryReward(u.getId(), 10, "TEST", null, key(), at)).isFalse();
        assertThat(service.grantedThisMonth(u.getId()))
                .as("🛡 开关关着连额度都不该被占用").isEqualTo(0);
        assertThat(wallet.balanceOf(u.getId())).isEqualTo(0);
    }

    /** 🔴 总开关优先于额度：即使额度全新，开关关着也一律不发。 */
    @Test
    void masterSwitchIsCheckedBeforeQuota() {
        User u = newUser();
        configure(true, 100);
        Instant at = Instant.now();
        assertThat(service.tryReward(u.getId(), 10, "TEST", null, key(), at)).isTrue();

        configure(false, 100);
        assertThat(service.tryReward(u.getId(), 10, "TEST", null, key(), at)).isFalse();
        assertThat(service.grantedThisMonth(u.getId())).isEqualTo(10); // 没再涨
    }

    /** 上限配成 0 = 本版不发（与开关关闭的效果一致，但保留语义区分）。 */
    @Test
    void zeroCapGrantsNothing() {
        User u = newUser();
        configure(true, 0);
        assertThat(service.tryReward(u.getId(), 5, "TEST", null, key(), Instant.now())).isFalse();
    }

    // ── 🛡 AC5：并发不超发 ──────────────────────────────────────

    /**
     * 🛡 十个线程同时发 10 枚、上限 50 ⇒ 只能成功 5 次，总量恰好 50。
     *
     * <p>🔴 这条要防的是应用层读改写导致的丢更新。所以必须打**真数据库**：
     * 不超发是那条 {@code WHERE granted + coins <= cap} 的行锁给的，
     * mock 里没有行锁，测了等于没测。
     */
    @Test
    void concurrentGrantsNeverExceedTheCap() throws Exception {
        User u = newUser();
        configure(true, 50);
        Instant at = Instant.now();
        int threads = 10;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> service.tryReward(u.getId(), 10, "TEST", null, key(), at));
            }
            List<Future<Boolean>> results = pool.invokeAll(tasks);
            long granted = 0;
            for (Future<Boolean> f : results) {
                if (f.get()) {
                    granted++;
                }
            }
            assertThat(granted).as("上限 50 / 每次 10 ⇒ 恰好 5 次成功").isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }

        assertThat(service.grantedThisMonth(u.getId())).isEqualTo(50);
        assertThat(wallet.balanceOf(u.getId())).as("🛡 记账与实际余额必须一致").isEqualTo(50);
    }

    /**
     * 🛡 AC5：额度计数与发币在**同一事务**内。
     *
     * <p>做法是把 {@code tryReward} 放进一个外层事务里，然后让外层事务回滚 ——
     * 如果计数走的是自己的事务（比如某天有人给它加了 {@code REQUIRES_NEW}），
     * 它会**存活下来**，于是出现「币回滚了、额度还记着」，用户白白少一笔额度。
     * 反过来若发币走自己的事务，就是「币发了、额度没记」＝无上限。
     * 两者都被这条钉住：回滚之后**两边都必须归零**。
     */
    @Test
    void quotaAndCreditShareOneTransaction() {
        User u = newUser();
        configure(true, 100);
        Instant at = Instant.now();

        try {
            new org.springframework.transaction.support.TransactionTemplate(txManager)
                    .execute(status -> {
                        boolean granted = service.tryReward(u.getId(), 40, "TEST", null, key(), at);
                        assertThat(granted).isTrue();
                        throw new IllegalStateException("故意回滚");
                    });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("故意回滚");
        }

        assertThat(service.grantedThisMonth(u.getId()))
                .as("🛡 外层回滚后额度计数必须一起没了").isEqualTo(0);
        assertThat(wallet.balanceOf(u.getId()))
                .as("🛡 外层回滚后余额也必须一起没了").isEqualTo(0);
    }

    // ── AC1：额度不按渠道分账 ────────────────────────────────────

    /**
     * 🛡 同一账号跨渠道共用**一份**额度。
     *
     * <p>用两个不同的 {@code refType} 模拟两个渠道 —— 它们必须消耗同一个额度池。
     * 按渠道各算一份等于「上限 × 渠道数」，而这一层存在的意义就是「一个账号一个月最多这么多」。
     */
    @Test
    void quotaIsSharedAcrossChannelsNotPerChannel() {
        User u = newUser();
        configure(true, 100);
        Instant at = Instant.now();

        assertThat(service.tryReward(u.getId(), 60, "ID_CARD_SHARE", null, key(), at)).isTrue();
        assertThat(service.tryReward(u.getId(), 60, "SOME_OTHER_CHANNEL", null, key(), at))
                .as("🛡 换个渠道不该另给一份额度").isFalse();
        assertThat(service.grantedThisMonth(u.getId())).isEqualTo(60);
        assertThat(quotas.findByUserIdAndPeriod(u.getId(), service.currentPeriod()))
                .as("一个账号一个 period 只该有一行（没有渠道维度）").isPresent();
    }

    /** 额度是按账号的：另一个账号不受影响。 */
    @Test
    void quotaIsPerAccount() {
        User a = newUser();
        User b = newUser();
        configure(true, 50);
        Instant at = Instant.now();

        assertThat(service.tryReward(a.getId(), 50, "TEST", null, key(), at)).isTrue();
        assertThat(service.tryReward(a.getId(), 10, "TEST", null, key(), at)).isFalse();
        assertThat(service.tryReward(b.getId(), 50, "TEST", null, key(), at))
                .as("别人的额度不该被占").isTrue();
    }

    @Test
    void nonPositiveCoinsIsRefused() {
        User u = newUser();
        configure(true, 100);
        assertThat(service.tryReward(u.getId(), 0, "TEST", null, key(), Instant.now())).isFalse();
        assertThat(service.tryReward(u.getId(), -5, "TEST", null, key(), Instant.now())).isFalse();
    }
}
