package com.tailtopia.shop.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.service.CouponAdminService;
import com.tailtopia.shop.coupon.service.CouponDraft;
import com.tailtopia.shop.coupon.service.CouponExpiryReminderJob;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：v1.4.2 shop Story 2.3 AC1 / AC5（需 Docker postgres + redis，云端不跑，待本地验收）。
 * 直调 {@link CouponExpiryReminderJob#run(Instant)}（基类已用 {@code petgo.shop.coupon.expiry-reminder-cron=-} 关掉定时触发）。
 *
 * <p>⚠️ 共享库：每个用例用新用户，断言只看本用例用户的行；{@code now} 取远未来的固定时刻并把券的到期拨进窗口，
 * 避免撞到别的测试遗留的券（候选 SQL 只看 {@code (now, now+48h]}）。
 */
class CouponExpiryReminderIntegrationTest extends ApiIntegrationTest {

    @Autowired private CouponExpiryReminderJob job;
    @Autowired private CouponLedgerService ledger;
    @Autowired private CouponAdminService admin;
    @Autowired private ShopCouponRepository coupons;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean
    private NotificationPusher pusher;

    /** 本类的「本轮开始时刻」：取 Instant.now() + 400 天整秒，每个用例再错开，避开共享库里别的券。 */
    private Instant baseNow() {
        return Instant.now().plus(Duration.ofDays(400 + SEQ.incrementAndGet() % 300)).truncatedTo(ChronoUnit.SECONDS);
    }

    private ShopCoupon newCoupon() {
        Instant now = Instant.now();
        return admin.create(new CouponDraft(CouponKind.DISCOUNT, "Diskon EXP", "Discount EXP", 10_000L, 50_000L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(10)), CouponValidMode.RELATIVE, null, null, 30, null, 10), null);
    }

    /** 发一张券并把到期拨到 {@code validUntil}。 */
    private UserCoupon issueExpiring(long userId, ShopCoupon c, Instant validUntil) {
        UserCoupon uc = tx.execute(s -> {
            ShopCoupon locked = coupons.findLockedById(c.getId()).orElseThrow();
            assertThat(coupons.incrementIssuedIfAvailable(locked.getId())).isEqualTo(1);
            return ledger.issue(userId, locked, UserCouponSource.CLAIM, Instant.now());
        });
        jdbc.update("UPDATE user_coupons SET valid_until = ? WHERE id = ?", Timestamp.from(validUntil), uc.getId());
        return uc;
    }

    private long notices(long uid) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'COUPON_EXPIRING'",
                Long.class, uid);
    }

    private boolean notified(UserCoupon uc) {
        return jdbc.queryForObject("SELECT expiry_notified_at IS NOT NULL FROM user_coupons WHERE id = ?",
                Boolean.class, uc.getId());
    }

    @Test
    void 窗口边界_只提醒落在now到now加48小时内的AVAILABLE券() {
        long uid = newUser().getId();
        ShopCoupon c = newCoupon();
        Instant now = baseNow();
        UserCoupon edge = issueExpiring(uid, c, now.plus(Duration.ofHours(48)));
        UserCoupon beyond = issueExpiring(uid, c, now.plus(Duration.ofHours(48)).plusSeconds(1));
        UserCoupon past = issueExpiring(uid, c, now.minusSeconds(1));
        UserCoupon voided = issueExpiring(uid, c, now.plus(Duration.ofHours(10)));
        jdbc.update("UPDATE user_coupons SET status = 'VOIDED' WHERE id = ?", voided.getId());

        job.run(now);

        assertThat(notified(edge)).isTrue();
        assertThat(notified(beyond)).isFalse();
        assertThat(notified(past)).isFalse();
        assertThat(notified(voided)).isFalse();
        assertThat(notices(uid)).isEqualTo(1);
    }

    @Test
    void 三张合并一条_同日重跑不新增_次日只算新进窗口的券() {
        long uid = newUser().getId();
        ShopCoupon c = newCoupon();
        Instant now = baseNow();
        for (int i = 1; i <= 3; i++) {
            issueExpiring(uid, c, now.plus(Duration.ofHours(10 * i)));
        }

        job.run(now);
        assertThat(notices(uid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT target_ref FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'COUPON_EXPIRING'", String.class, uid)).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT title FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'COUPON_EXPIRING'", String.class, uid)).isEqualTo("3 kupon segera berakhir");

        job.run(now.plusSeconds(60));
        assertThat(notices(uid)).as("同一 WIB 日不新增").isEqualTo(1);

        Instant tomorrow = now.plus(Duration.ofDays(1));
        job.run(tomorrow);
        assertThat(notices(uid)).as("昨天已提醒过的券不再计入").isEqualTo(1);

        // 后天（必是另一个 WIB 日）：窗口 (now+48h, now+96h] 只有这张新券
        issueExpiring(uid, c, tomorrow.plus(Duration.ofHours(40)));
        job.run(now.plus(Duration.ofDays(2)));
        assertThat(notices(uid)).as("新进窗口的券触发新的一条").isEqualTo(2);
    }

    @Test
    void 关闭PROMO_通知中心有行_不推送() {
        long uid = newUser().getId();
        jdbc.update("INSERT INTO user_push_mutes (user_id, category) VALUES (?, 'PROMO')", uid);
        ShopCoupon c = newCoupon();
        Instant now = baseNow();
        issueExpiring(uid, c, now.plus(Duration.ofHours(5)));
        clearInvocations(pusher);

        job.run(now);

        assertThat(notices(uid)).isEqualTo(1);
        verify(pusher, never()).pushToUser(eq(uid), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 两种新类型写库不触发23514() {
        long uid = newUser().getId();
        for (String type : List.of("COUPON_EXPIRING", "SHOP_ORDER_REORDERED")) {
            jdbc.update("INSERT INTO notifications (recipient_user_id, type, title, body, deep_link_type, deep_link_token, "
                    + "target_ref, created_at) VALUES (?, ?, 't', 'b', ?, ?, '1', now())",
                    uid, type, type, "ckt-" + SEQ.incrementAndGet());
        }
    }

    @Test
    void advisoryLock_两线程同时跑只有一个真正处理() throws Exception {
        long uid = newUser().getId();
        ShopCoupon c = newCoupon();
        Instant now = baseNow();
        issueExpiring(uid, c, now.plus(Duration.ofHours(5)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<CouponExpiryReminderJob.Result>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    return job.run(now);
                }));
            }
            start.countDown();
            for (Future<CouponExpiryReminderJob.Result> f : fs) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(notices(uid)).isEqualTo(1);
    }
}
