package com.tailtopia.share.service;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.share.domain.ShareRewardQuota;
import com.tailtopia.share.repository.ShareRewardQuotaRepository;
import java.time.YearMonth;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分享奖励发放闸门（V1.1.6 Story 18.1）。
 *
 * <h2>范围严格限定在「上限控制」（AC2）</h2>
 * 🛡 <b>不做</b>任务列表 / 进度展示 / 领取动作 / 任务中心 / 可扩展的「任务→奖励」模型 ——
 * 那些是 FR-50B 的范围（编号保留、延期中）。在这里顺手做等于提前实现一个未定稿的需求。
 * 🔴 本层是 FR-50B 的**前置能力**，先行交付。
 *
 * <h2>闸门顺序：总开关 → 月度上限 → 放行</h2>
 * 🔴 总开关必须在最前，且比任何渠道层配置优先（AC6）——
 * 它存在的唯一理由是「发现被刷要能立刻全线关掉」。
 *
 * <h2>🛡 AC5：计数与发币同一事务</h2>
 * 本方法是 {@code @Transactional}，{@link PawCoinWalletService#credit} 也是（默认 REQUIRED）
 * ⇒ 二者<b>合并进同一个事务</b>。不同事务的后果是「币发了、额度没记」，等于无上限。
 * 并发不超发靠仓储那条原子条件 UPDATE 的行锁，不靠应用层读改写。
 *
 * <h2>⚠️ AC3：达上限后不告知</h2>
 * 达上限返回 {@code false}，调用方据此<b>不展示「+N」提示</b>，
 * 但<b>分享本身照常成功</b>。🛡 不发任何「你的额度用完了」——
 * 告知会诱导「攒着别分享」或「月初集中刷满」。
 */
@Service
public class ShareRewardService {

    private static final Logger log = LoggerFactory.getLogger(ShareRewardService.class);

    /**
     * WIB（印尼西部时间）。月界按此算，<b>刻意偏离项目全局 UTC 惯例</b>——
     * 与 {@code user_monthly_free_quota} 完全同一口径（Story 2.1 已定死）。
     * 🔴 UTC 月初 = WIB 月初早上 7 点，按 UTC 切会在月初那天错发一整批。<b>勿订正</b>。
     */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final ShareRewardQuotaRepository quotas;
    private final PlatformConfigService platformConfig;
    private final PawCoinWalletService wallet;

    public ShareRewardService(ShareRewardQuotaRepository quotas,
            PlatformConfigService platformConfig, PawCoinWalletService wallet) {
        this.quotas = quotas;
        this.platformConfig = platformConfig;
        this.wallet = wallet;
    }

    /**
     * 某个时刻属于哪个月度 period（{@code YYYY-MM}，WIB）。**纯函数，唯一实现**。
     *
     * <p>⚠️ 月界判定只在这一处：写两遍就会出现「发放按 WIB 切、统计按 UTC 切」，
     * 而那种不一致只在月初那 7 小时里显形，平时看不出来。
     */
    public static String periodOf(java.time.Instant at) {
        return YearMonth.from(at.atZone(WIB)).toString();
    }

    /** 当前月度 period。换月自然产生新行 = 惰性重置，不需要 {@code @Scheduled}。 */
    public String currentPeriod() {
        return periodOf(java.time.Instant.now());
    }

    /**
     * 试着为一次分享行为发放 {@code coins} 枚 PawCoin。
     *
     * <p>返回 {@code true} = 真的发了（调用方可以展示「+N」轻提示）；
     * {@code false} = 没发（总开关关闭 / 本月额度不够）。
     * 🛡 <b>两种 false 对调用方是同一件事</b>：都只是「不展示提示」，
     * 刻意不区分原因，就是为了让调用方没法把「额度用完」讲给用户听（AC3）。
     *
     * <p>⚠️ {@code idempotencyKey} 是<b>渠道层</b>的去重键（如「按档案只发一次」），
     * 由调用方给。本层只管上限，不定义去重语义。
     *
     * @return 是否真的发了币
     */
    @Transactional
    public boolean tryReward(long userId, long coins, String refType, Long refId,
            String idempotencyKey) {
        return tryReward(userId, coins, refType, refId, idempotencyKey, java.time.Instant.now());
    }

    /**
     * 显式传入时刻的重载。
     *
     * <p>⚠️ {@code at} 只决定<b>落在哪个月度 period</b>，不参与发币本身。
     * 传入时刻而不是在内部取 {@code now()}，是为了让「WIB 月界」这条 AC 真的可测 ——
     * 沿用 17.1 {@code RankThrottleService.factorsFor(targets, now)} 的同一形状。
     */
    @Transactional
    public boolean tryReward(long userId, long coins, String refType, Long refId,
            String idempotencyKey, java.time.Instant at) {
        if (coins <= 0) {
            return false;
        }
        var cfg = platformConfig.pawcoin();
        // 🔴 AC6：总开关在最前，优先于一切。
        if (!cfg.isShareRewardEnabled()) {
            return false;
        }
        long cap = cfg.getShareRewardMonthlyCap();
        if (cap <= 0) {
            return false; // 0 = 本版不发（等价于关掉，但保留开关的语义区分）
        }
        String period = periodOf(at);
        quotas.insertIfAbsent(userId, period);
        if (quotas.tryGrant(userId, period, coins, cap) != 1) {
            // 达上限。⚠️ 不发币、不提示、不告知；分享本身在调用方那边照常成功。
            // 只记 debug —— 达上限是**预期行为**，不是告警。
            log.debug("分享奖励已达本月上限 user={} period={} cap={}", userId, period, cap);
            return false;
        }
        // 🛡 与上面的占额在**同一事务**内（AC5）：credit 也是 @Transactional REQUIRED，会合并。
        // 这里若抛异常，占额一并回滚 —— 不会出现「额度记了、币没发」。
        wallet.credit(userId, coins, PawCoinTxnType.BONUS, refType, refId, idempotencyKey);
        return true;
    }

    /** 本月已发放量（只读，不建行）。供 18-3 的后台统计与测试用。 */
    @Transactional(readOnly = true)
    public long grantedThisMonth(long userId) {
        return quotas.findByUserIdAndPeriod(userId, currentPeriod())
                .map(ShareRewardQuota::getGrantedCoins)
                .orElse(0L);
    }

    /** 本月剩余额度（只读）。🛡 <b>不对用户侧暴露</b>——AC3 明令不告知。 */
    @Transactional(readOnly = true)
    public long remainingThisMonth(long userId) {
        return Math.max(0, platformConfig.pawcoin().getShareRewardMonthlyCap()
                - grantedThisMonth(userId));
    }
}
