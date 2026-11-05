package com.tailtopia.shop.service;

import com.tailtopia.shop.address.repository.ShippingAddressRepository;
import com.tailtopia.shop.cart.repository.ShopCartItemRepository;
import com.tailtopia.shop.cart.repository.ShopCartRepository;
import com.tailtopia.shop.coupon.repository.CouponExpiryNotifyLogDao;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * shop 模块注销级联（Story 7.3，1.1.6 电商补齐，D1/D2 口径）。
 *
 * <p>分类照 D1 的「纯个人数据删除 / 交易记录匿名化保留」两分法：
 * <ul>
 *   <li><b>物理删除</b>：{@code shipping_addresses}（🔒 收件人姓名/电话/地址，纯个人 PII）、
 *       {@code shop_carts} + 车行（个人偏好数据，无留存价值）；</li>
 *   <li><b>匿名化保留</b>：{@code shop_orders} 照 consult_orders 例保留交易/财务记录，
 *       只剥收货地址快照三项 PII；{@code return_requests} 流程记录保留，
 *       🔒 加密收款账号/户名置空。</li>
 *   <li><b>作废保留</b>（v1.4.2 shop Story 2.1 AC7）：{@code user_coupons} 可用券 AVAILABLE → VOIDED
 *       （经 {@code CouponLedgerService}，用户券无 PII，行保留供订单追溯；LOCKED / USED 随订单保留不动），
 *       {@code coupon_expiry_notify_log} 物理删除（避免 2.3 给已注销用户扫出快过期券）。</li>
 * </ul>
 *
 * <p>幂等可重跑（注销作业半途失败会 FAILED 重试）。日志只记计数，绝不落 PII。
 */
@Service
public class ShopAccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(ShopAccountDeletionService.class);

    private final ShippingAddressRepository addresses;
    private final ShopCartRepository carts;
    private final ShopCartItemRepository cartItems;
    private final ShopOrderRepository orders;
    private final ReturnRequestRepository returns;
    private final CouponLedgerService couponLedger;
    private final CouponExpiryNotifyLogDao couponNotifyLog;

    public ShopAccountDeletionService(ShippingAddressRepository addresses,
            ShopCartRepository carts, ShopCartItemRepository cartItems,
            ShopOrderRepository orders, ReturnRequestRepository returns,
            CouponLedgerService couponLedger, CouponExpiryNotifyLogDao couponNotifyLog) {
        this.addresses = addresses;
        this.carts = carts;
        this.cartItems = cartItems;
        this.orders = orders;
        this.returns = returns;
        this.couponLedger = couponLedger;
        this.couponNotifyLog = couponNotifyLog;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        addresses.deleteByUserId(userId);
        carts.findByUserId(userId).ifPresent(cart -> {
            cartItems.deleteByCartId(cart.getId());
            carts.delete(cart);
        });
        int ordersAnonymized = orders.anonymizeShipSnapshotByUserId(userId);
        int payoutsCleared = returns.clearPayoutPiiByUserId(userId);
        int couponsVoided = couponLedger.voidAllAvailableForUser(userId, Instant.now());
        int notifyLogsDeleted = couponNotifyLog.deleteByUserId(userId);
        log.info("注销联动电商清理 orders={} payouts={} couponsVoided={} couponNotifyLogs={}",
                ordersAnonymized, payoutsCleared, couponsVoided, notifyLogsDeleted);
    }
}
