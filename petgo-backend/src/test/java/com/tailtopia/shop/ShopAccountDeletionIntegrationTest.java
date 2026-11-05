package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.domain.UserCoupon;
import com.tailtopia.shop.coupon.domain.UserCouponSource;
import com.tailtopia.shop.coupon.domain.UserCouponStatus;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import com.tailtopia.shop.coupon.service.CouponAdminService;
import com.tailtopia.shop.coupon.service.CouponDraft;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.service.ShopAccountDeletionService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** L1：v1.4.2 shop Story 2.1 AC7 —— 注销联动券作废与提醒日志清理（需 Docker postgres + redis，云端不跑）。 */
class ShopAccountDeletionIntegrationTest extends ApiIntegrationTest {

    @Autowired private ShopAccountDeletionService deletion;
    @Autowired private CouponAdminService admin;
    @Autowired private CouponLedgerService ledger;
    @Autowired private ShopCouponRepository coupons;
    @Autowired private UserCouponRepository userCoupons;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private UserCoupon issue(long uid, ShopCoupon c) {
        return tx.execute(s -> {
            ShopCoupon locked = coupons.findLockedById(c.getId()).orElseThrow();
            coupons.incrementIssuedIfAvailable(locked.getId());
            return ledger.issue(uid, locked, UserCouponSource.CLAIM, Instant.now());
        });
    }

    @Test
    void deletionVoidsAvailableKeepsLockedAndClearsNotifyLog() {
        long uid = newUser().getId();
        Instant now = Instant.now();
        ShopCoupon c = admin.create(new CouponDraft(CouponKind.DISCOUNT, "Diskon", "Discount", 5_000L, 0L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(5)), CouponValidMode.RELATIVE, null, null, 7, null, 3), null);
        UserCoupon idle = issue(uid, c);
        UserCoupon held = issue(uid, c);
        long n = SEQ.incrementAndGet();
        long order = jdbc.queryForObject("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING_PAYMENT', 100000, 0, 0, 100000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', now(), now())
                RETURNING id""", Long.class, "deit" + n, "TOKO-DE-" + n % 100_000_000, uid);
        tx.executeWithoutResult(s -> ledger.lock(uid, order, List.of(held.getPublicToken()), Instant.now()));
        jdbc.update("INSERT INTO coupon_expiry_notify_log (user_id, wib_date, coupon_count) VALUES (?, CURRENT_DATE, 1)", uid);

        deletion.deleteByUserId(uid);

        assertThat(userCoupons.findById(idle.getId()).orElseThrow().getStatus()).isEqualTo(UserCouponStatus.VOIDED);
        assertThat(userCoupons.findById(held.getId()).orElseThrow().getStatus()).isEqualTo(UserCouponStatus.LOCKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coupon_expiry_notify_log WHERE user_id = ?",
                Integer.class, uid)).isZero();
    }
}
