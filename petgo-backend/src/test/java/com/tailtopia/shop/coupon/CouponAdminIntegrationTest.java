package com.tailtopia.shop.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.service.CouponAdminService;
import com.tailtopia.shop.coupon.service.CouponDraft;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** L1：v1.4.2 shop Story 2.1 AC3 —— 新人券并发唯一、条件扣总量（需 Docker postgres + redis，云端不跑）。 */
class CouponAdminIntegrationTest extends ApiIntegrationTest {

    @Autowired private CouponAdminService admin;
    @Autowired private ShopCouponRepository coupons;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MessageSource messageSource;

    private static CouponDraft newbie(Instant start, Instant end) {
        return new CouponDraft(CouponKind.DISCOUNT, "Baru", "Newbie", 10_000L, 0L, CouponScopeType.ALL, null,
                CouponAcquireType.NEWBIE, start, end, CouponValidMode.RELATIVE, null, null, 7, null, 1);
    }

    @Test
    void concurrentOverlappingNewbieCouponsOnlyOneCreated() throws Exception {
        // 远未来的发放期，避开共享测试库里其它测试建过的新人券
        Instant start = Instant.now().plus(Duration.ofDays(3650 + SEQ.incrementAndGet() % 1000 * 30))
                .truncatedTo(ChronoUnit.SECONDS);
        Instant end = start.plus(Duration.ofDays(10));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    admin.create(newbie(start, end), null);
                    return true;
                } catch (AppException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int created = 0;
        for (Future<Boolean> f : results) {
            created += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM shop_coupons WHERE acquire_type = 'NEWBIE' "
                + "AND status = 'ACTIVE' AND issue_start_at = ?", Integer.class, java.sql.Timestamp.from(start));
        assertThat(rows).isEqualTo(1);
    }

    /** 2026-11-10 报障：后台切成英文 / 印尼语，券保存的校验错误仍是中文 —— 每条都要带文案码。 */
    @Test
    void validationErrorsCarryLocalizedMessageCodes() {
        Instant now = Instant.now();
        AppException belowMin = catchThrowableOfType(AppException.class, () -> admin.create(new CouponDraft(
                CouponKind.DISCOUNT, "Ikan teri", "Bone", 50_000L, 50_000L, CouponScopeType.ALL, null,
                CouponAcquireType.CLAIM, now, now.plusSeconds(86400), CouponValidMode.RELATIVE, null, null, 5, 5, 1),
                null));
        assertThat(belowMin.getMessageCode()).isEqualTo("admin.err.coupon.amountBelowMinSpend");
        assertThat(messageSource.getMessage(belowMin.getMessageCode(), belowMin.getMessageArgs(), Locale.ENGLISH))
                .startsWith("For a spend discount coupon");
        assertThat(messageSource.getMessage(belowMin.getMessageCode(), belowMin.getMessageArgs(),
                Locale.forLanguageTag("id"))).startsWith("Untuk kupon potongan belanja");

        AppException noName = catchThrowableOfType(AppException.class, () -> admin.create(new CouponDraft(
                CouponKind.DISCOUNT, " ", "Bone", 5_000L, 50_000L, CouponScopeType.ALL, null,
                CouponAcquireType.CLAIM, now, now.plusSeconds(86400), CouponValidMode.RELATIVE, null, null, 5, 5, 1),
                null));
        assertThat(noName.getMessageCode()).isEqualTo("admin.err.coupon.required.nameId");
    }

    @Test
    void incrementStopsAtTotalQuantity() {
        Instant now = Instant.now();
        ShopCoupon c = admin.create(new CouponDraft(CouponKind.FREE_SHIPPING, "Ongkir", "Shipping", 15_000L, 0L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, now.minusSeconds(60), now.plusSeconds(86400),
                CouponValidMode.RELATIVE, null, null, 3, 2, 1), null);
        assertThat((Integer) tx.execute(s -> coupons.incrementIssuedIfAvailable(c.getId()))).isEqualTo(1);
        assertThat((Integer) tx.execute(s -> coupons.incrementIssuedIfAvailable(c.getId()))).isEqualTo(1);
        assertThat((Integer) tx.execute(s -> coupons.incrementIssuedIfAvailable(c.getId()))).isZero();
        assertThat(coupons.findById(c.getId()).orElseThrow().getIssuedCount()).isEqualTo(2);
    }

    @Test
    void secondStopDoesNotOverwriteVoidChoice() {
        Instant now = Instant.now();
        ShopCoupon c = admin.create(new CouponDraft(CouponKind.DISCOUNT, "Stop", "Stop", 5_000L, 0L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, now.minusSeconds(60), now.plusSeconds(86400),
                CouponValidMode.RELATIVE, null, null, 3, null, 1), null);
        admin.stop(c.getPublicToken(), false, null);
        admin.stop(c.getPublicToken(), true, null);
        ShopCoupon after = coupons.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(com.tailtopia.shop.coupon.domain.CouponStatus.STOPPED);
        assertThat(after.isVoidOnStop()).isFalse();
        assertThat(after.getStoppedAt()).isNotNull();
    }
}
