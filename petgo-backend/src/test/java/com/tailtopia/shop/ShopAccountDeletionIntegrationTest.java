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
import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinAccountDeletionService;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
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
    @Autowired private PawCoinWalletService wallet;
    @Autowired private PawCoinAccountDeletionService pawCoinDeletion;
    @Autowired private AccountDeletionRepository accountDeletions;
    @Autowired private ShopOrderPaymentService payments;

    /** 新结算模式下的待付款单（checkout_source=CART，币段 {@code coin}，提交时已扣）。 */
    private long pendingNewModeOrder(long uid, long coin, boolean debit) {
        long n = SEQ.incrementAndGet();
        String token = "dnm" + n;
        long id = jdbc.queryForObject("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount, pay_channel, coin_amount, cash_amount,
                        checkout_source, expires_at,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING_PAYMENT', 100000, 0, 0, 100000, 'MIXED', ?, ?, 'CART',
                        now() - interval '1 minute',
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', now(), now())
                RETURNING id""", Long.class, token, "TOKO-DN-" + n % 100_000_000, uid, coin, 100_000L - coin);
        if (debit) {
            wallet.debit(uid, coin, PawCoinTxnType.SPEND, "SHOP_ORDER", id, "shop-order:" + token);
        }
        return id;
    }

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
        // v1.4.2 shop Story 3.2 复审修复（D1/D2）：占用它的是【待付款】单 → 注销先取消该单、券退回 AVAILABLE，
        // 再随可用券一并作废（原断言 LOCKED 随订单保留，只对已付款订单成立）。
        assertThat(userCoupons.findById(held.getId()).orElseThrow().getStatus()).isEqualTo(UserCouponStatus.VOIDED);
        assertThat(jdbc.queryForObject("SELECT status FROM shop_orders WHERE id = ?", String.class, order))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM coupon_expiry_notify_log WHERE user_id = ?",
                Integer.class, uid)).isZero();
    }

    @Test
    void deletionCancelsPendingOrderBeforeCoinForfeitAndLateCancelNeverRecreatesWallet() {
        long uid = newUser().getId();
        wallet.credit(uid, 50_000L, PawCoinTxnType.TOPUP, "TEST", null, "del-topup:" + SEQ.incrementAndGet());
        long first = pendingNewModeOrder(uid, 30_000L, true);
        assertThat(wallet.balanceOf(uid)).isEqualTo(20_000L);

        // 注销编排的顺序：受理（account_deletions 有行）→ shop 联动 → … → PawCoin 作废
        accountDeletions.save(AccountDeletion.request(uid));
        deletion.deleteByUserId(uid);
        assertThat(jdbc.queryForObject("SELECT status FROM shop_orders WHERE id = ?", String.class, first))
                .isEqualTo("CANCELLED");
        assertThat(wallet.balanceOf(uid)).as("币先退回，随后随钱包一并作废").isEqualTo(50_000L);
        pawCoinDeletion.voidBalanceAndPurge(uid);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pawcoin_wallets WHERE user_id = ?",
                Integer.class, uid)).isZero();

        // 竞态：注销之后才出现（或漏网）的待付款单被超时扫描关掉 —— 不得重建钱包、不得写退币流水
        //    （币段视为提交时已扣、钱包已随注销作废，故不再造扣币流水）
        long late = pendingNewModeOrder(uid, 30_000L, false);
        payments.cancelOneOverdue(late);
        assertThat(jdbc.queryForObject("SELECT status FROM shop_orders WHERE id = ?", String.class, late))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pawcoin_wallets WHERE user_id = ?",
                Integer.class, uid)).as("🔴 不给已注销账号重建钱包").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pawcoin_transactions WHERE user_id = ? AND ref_type = 'SHOP_ORDER_VOID'
                """, Integer.class, uid)).isZero();
    }
}
