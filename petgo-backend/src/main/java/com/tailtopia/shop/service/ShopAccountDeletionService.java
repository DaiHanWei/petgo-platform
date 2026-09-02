package com.tailtopia.shop.service;

import com.tailtopia.shop.address.repository.ShippingAddressRepository;
import com.tailtopia.shop.cart.repository.ShopCartItemRepository;
import com.tailtopia.shop.cart.repository.ShopCartRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
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

    public ShopAccountDeletionService(ShippingAddressRepository addresses,
            ShopCartRepository carts, ShopCartItemRepository cartItems,
            ShopOrderRepository orders, ReturnRequestRepository returns) {
        this.addresses = addresses;
        this.carts = carts;
        this.cartItems = cartItems;
        this.orders = orders;
        this.returns = returns;
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
        log.info("注销联动电商清理 orders={} payouts={}", ordersAnonymized, payoutsCleared);
    }
}
