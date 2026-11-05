package com.tailtopia.shop.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.address.repository.ShippingAddressRepository;
import com.tailtopia.shop.cart.repository.ShopCartItemRepository;
import com.tailtopia.shop.cart.repository.ShopCartRepository;
import com.tailtopia.shop.coupon.repository.CouponExpiryNotifyLogDao;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** L0：v1.4.2 shop Story 2.1 AC7 —— 注销联动在既有清理之后作废可用券、删提醒日志（既有顺序不变）。 */
class ShopAccountDeletionServiceTest {

    @Test
    void voidsCouponsAndClearsNotifyLogAfterExistingSteps() {
        ShippingAddressRepository addresses = mock(ShippingAddressRepository.class);
        ShopCartRepository carts = mock(ShopCartRepository.class);
        ShopCartItemRepository cartItems = mock(ShopCartItemRepository.class);
        ShopOrderRepository orders = mock(ShopOrderRepository.class);
        ReturnRequestRepository returns = mock(ReturnRequestRepository.class);
        CouponLedgerService ledger = mock(CouponLedgerService.class);
        CouponExpiryNotifyLogDao notifyLog = mock(CouponExpiryNotifyLogDao.class);
        when(carts.findByUserId(5L)).thenReturn(Optional.empty());

        new ShopAccountDeletionService(addresses, carts, cartItems, orders, returns, ledger, notifyLog)
                .deleteByUserId(5L);

        InOrder order = inOrder(addresses, orders, returns, ledger, notifyLog);
        order.verify(addresses).deleteByUserId(5L);
        order.verify(orders).anonymizeShipSnapshotByUserId(5L);
        order.verify(returns).clearPayoutPiiByUserId(5L);
        order.verify(ledger).voidAllAvailableForUser(eq(5L), any(Instant.class));
        order.verify(notifyLog).deleteByUserId(5L);
    }
}
