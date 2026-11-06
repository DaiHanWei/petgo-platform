package com.tailtopia.shop.service;

import com.tailtopia.shop.address.repository.ShippingAddressRepository;
import com.tailtopia.shop.cart.repository.ShopCartItemRepository;
import com.tailtopia.shop.cart.repository.ShopCartRepository;
import com.tailtopia.shop.coupon.repository.CouponExpiryNotifyLogDao;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
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
 *       🔒 加密收款账号/户名置空；{@code shop_order_refunds}（v1.4.2 shop 4.1）同样只清加密收款账号/户名。</li>
 *   <li><b>作废保留</b>（v1.4.2 shop Story 2.1 AC7）：{@code user_coupons} 可用券 AVAILABLE → VOIDED
 *       （经 {@code CouponLedgerService}，用户券无 PII，行保留供订单追溯；LOCKED / USED 随订单保留不动），
 *       {@code coupon_expiry_notify_log} 物理删除（避免 2.3 给已注销用户扫出快过期券）。</li>
 * </ul>
 *
 * <p>🔴 v1.4.2 shop Story 3.2 复审修复（D1/D2，S-3 提交即扣币后）：<b>最先</b>取消该用户全部待付款单
 * （{@code ShopOrderPaymentService#releaseUnpaidForAccountDeletion}）—— 提交时扣下的币退回钱包、LOCKED 券退回
 * AVAILABLE、库存释放、意图作废；随后本方法作废可用券，编排里（{@code AccountDeletionService#execute}）更靠后的
 * {@code PawCoinAccountDeletionService#voidBalanceAndPurge} 把退回的币一并作废。否则超时关单会在注销完成后
 * 给已注销账号重建钱包、复活券。
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
    private final ShopOrderPaymentService payments;
    private final com.tailtopia.shop.order.refund.ShopOrderRefundRepository orderRefunds;

    public ShopAccountDeletionService(ShippingAddressRepository addresses,
            ShopCartRepository carts, ShopCartItemRepository cartItems,
            ShopOrderRepository orders, ReturnRequestRepository returns,
            CouponLedgerService couponLedger, CouponExpiryNotifyLogDao couponNotifyLog,
            ShopOrderPaymentService payments,
            com.tailtopia.shop.order.refund.ShopOrderRefundRepository orderRefunds) {
        this.addresses = addresses;
        this.carts = carts;
        this.cartItems = cartItems;
        this.orders = orders;
        this.returns = returns;
        this.couponLedger = couponLedger;
        this.couponNotifyLog = couponNotifyLog;
        this.payments = payments;
        this.orderRefunds = orderRefunds;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        // ⓪ 待付款单先取消（退币、退券、释放库存）—— 必须在可用券作废与 PawCoin 作废之前
        int pendingCancelled = 0;
        for (ShopOrder o : orders.findByUserIdAndStatus(userId, ShopOrderStatus.PENDING_PAYMENT)) {
            payments.releaseUnpaidForAccountDeletion(o);
            pendingCancelled++;
        }
        addresses.deleteByUserId(userId);
        carts.findByUserId(userId).ifPresent(cart -> {
            cartItems.deleteByCartId(cart.getId());
            carts.delete(cart);
        });
        int ordersAnonymized = orders.anonymizeShipSnapshotByUserId(userId);
        int payoutsCleared = returns.clearPayoutPiiByUserId(userId);
        // v1.4.2 shop Story 4.1：取消退款单的加密收款账号 / 户名同样置空（行保留供财务追溯）
        payoutsCleared += orderRefunds.clearPayoutPiiByUserId(userId);
        int couponsVoided = couponLedger.voidAllAvailableForUser(userId, Instant.now());
        int notifyLogsDeleted = couponNotifyLog.deleteByUserId(userId);
        log.info("注销联动电商清理 pendingCancelled={} orders={} payouts={} couponsVoided={} couponNotifyLogs={}",
                pendingCancelled, ordersAnonymized, payoutsCleared, couponsVoided, notifyLogsDeleted);
    }
}
