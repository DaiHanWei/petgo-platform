package com.tailtopia.shop.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponReleaseMode;
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
import com.tailtopia.shop.coupon.service.CouponInvalidException;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：v1.4.2 shop Story 2.1 AC1 / AC4 / AC5 —— 账本落库行为（需 Docker postgres + redis，云端不跑）。
 * {@code ddl-auto=validate} 启动本身即验证四张表与实体映射一致。
 */
class CouponLedgerIntegrationTest extends ApiIntegrationTest {

    @Autowired private CouponLedgerService ledger;
    @Autowired private CouponAdminService admin;
    @Autowired private ShopCouponRepository coupons;
    @Autowired private UserCouponRepository userCoupons;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private ShopCoupon newCoupon(int validDays) {
        Instant now = Instant.now();
        return admin.create(new CouponDraft(CouponKind.DISCOUNT, "Diskon IT", "Discount IT", 10_000L, 50_000L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(10)), CouponValidMode.RELATIVE, null, null, validDays, null, 5), null);
    }

    private UserCoupon issue(long userId, ShopCoupon c) {
        return tx.execute(s -> {
            ShopCoupon locked = coupons.findLockedById(c.getId()).orElseThrow();
            assertThat(coupons.incrementIssuedIfAvailable(locked.getId())).isEqualTo(1);
            return ledger.issue(userId, locked, UserCouponSource.CLAIM, Instant.now());
        });
    }

    private long newOrder(long userId) {
        long n = SEQ.incrementAndGet();
        return jdbc.queryForObject("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING_PAYMENT', 100000, 0, 0, 100000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', now(), now())
                RETURNING id""", Long.class, "cpit" + n, "TOKO-CP-" + n % 100_000_000, userId);
    }

    private UserCoupon reload(UserCoupon uc) {
        return userCoupons.findById(uc.getId()).orElseThrow();
    }

    private void lock(long userId, long orderId, UserCoupon uc, Instant at) {
        tx.executeWithoutResult(s -> ledger.lock(userId, orderId, List.of(uc.getPublicToken()), at));
    }

    @Test
    void concurrentLockOnlyOneWins() throws Exception {
        long uid = newUser().getId();
        UserCoupon uc = issue(uid, newCoupon(7));
        long o1 = newOrder(uid);
        long o2 = newOrder(uid);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> results = new ArrayList<>();
        for (long order : List.of(o1, o2)) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    lock(uid, order, uc, Instant.now());
                    return true;
                } catch (CouponInvalidException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int wins = 0;
        for (Future<Boolean> f : results) {
            wins += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        assertThat(wins).isEqualTo(1);
        assertThat(reload(uc).getStatus()).isEqualTo(UserCouponStatus.LOCKED);
    }

    @Test
    void markUsedIdempotentAndReturnClearsOrderFields() {
        long uid = newUser().getId();
        UserCoupon uc = issue(uid, newCoupon(7));
        long order = newOrder(uid);
        lock(uid, order, uc, Instant.now());
        assertThat((Integer) tx.execute(s -> ledger.markUsed(order, Instant.now()))).isEqualTo(1);
        assertThat((Integer) tx.execute(s -> ledger.markUsed(order, Instant.now()))).isZero();
        assertThat(reload(uc).getStatus()).isEqualTo(UserCouponStatus.USED);
        assertThat(reload(uc).getUsedOrderId()).isEqualTo(order);

        assertThat((Integer) tx.execute(s -> ledger.releaseOnCancel(order, CouponReleaseMode.KEEP, Instant.now()))).isZero();
        assertThat(reload(uc).getStatus()).isEqualTo(UserCouponStatus.USED);
        assertThat((Integer) tx.execute(s -> ledger.releaseOnCancel(order, CouponReleaseMode.RETURN, Instant.now()))).isEqualTo(1);
        UserCoupon back = reload(uc);
        assertThat(back.getStatus()).isEqualTo(UserCouponStatus.AVAILABLE);
        assertThat(back.getLockedOrderId()).isNull();
        assertThat(back.getUsedOrderId()).isNull();
    }

    @Test
    void stoppedWithVoidTurnsLockedIntoVoidedOnReturn() {
        long uid = newUser().getId();
        ShopCoupon c = newCoupon(7);
        UserCoupon locked = issue(uid, c);
        UserCoupon idle = issue(uid, c);
        long order = newOrder(uid);
        lock(uid, order, locked, Instant.now());

        admin.stop(c.getPublicToken(), true, null);
        assertThat(reload(idle).getStatus()).isEqualTo(UserCouponStatus.VOIDED);
        assertThat(reload(locked).getStatus()).isEqualTo(UserCouponStatus.LOCKED);

        tx.execute(s -> ledger.releaseOnCancel(order, CouponReleaseMode.RETURN, Instant.now()));
        assertThat(reload(locked).getStatus()).isEqualTo(UserCouponStatus.VOIDED);
        assertThat(reload(locked).getLockedOrderId()).isNull();
    }

    @Test
    void stoppedWithoutVoidStillLockable() {
        long uid = newUser().getId();
        ShopCoupon c = newCoupon(7);
        UserCoupon uc = issue(uid, c);
        admin.stop(c.getPublicToken(), false, null);
        long order = newOrder(uid);
        lock(uid, order, uc, Instant.now());
        assertThat(reload(uc).getStatus()).isEqualTo(UserCouponStatus.LOCKED);
    }

    @Test
    void expiredCannotLockButMarkUsedIgnoresExpiry() {
        long uid = newUser().getId();
        UserCoupon uc = issue(uid, newCoupon(1));
        long order = newOrder(uid);
        Instant afterExpiry = uc.getValidUntil().plusSeconds(1);
        assertThatThrownBy(() -> lock(uid, order, uc, afterExpiry)).isInstanceOf(CouponInvalidException.class);

        lock(uid, order, uc, Instant.now());
        assertThat((Integer) tx.execute(s -> ledger.markUsed(order, afterExpiry))).isEqualTo(1);
    }

    @Test
    void otherUsersCouponCannotBeLocked() {
        long owner = newUser().getId();
        long other = newUser().getId();
        UserCoupon uc = issue(owner, newCoupon(7));
        long order = newOrder(other);
        assertThatThrownBy(() -> lock(other, order, uc, Instant.now())).isInstanceOf(CouponInvalidException.class);
    }

    @Test
    void deletingOrdersIsNotBlockedByCouponFks() {
        long uid = newUser().getId();
        UserCoupon uc = issue(uid, newCoupon(7));
        long order = newOrder(uid);
        lock(uid, order, uc, Instant.now());
        assertThat(jdbc.update("DELETE FROM shop_orders WHERE id = ?", order)).isEqualTo(1);
        assertThat(reload(uc).getLockedOrderId()).isNull();
    }
}
