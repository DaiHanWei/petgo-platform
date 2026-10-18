package com.tailtopia.gift.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.gift.service.GiftTestFixtures;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentFailedEvent;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 gift Story 1.1 · AC8.5（L0）：EXPIRED → EXPIRED；DECLINED / CANCELLED → FAILED；非 PENDING 不动。 */
class GiftPaymentFailedHandlerTest {

    private GiftOrderRepository orders;
    private GiftPaymentFailedHandler handler;
    private GiftOrder order;

    @BeforeEach
    void setUp() {
        orders = mock(GiftOrderRepository.class);
        handler = new GiftPaymentFailedHandler(orders);
        order = GiftTestFixtures.order(51L, PayChannel.QRIS, 1, 10_000);
        order.attachPaymentIntent(99L);
        when(orders.findByPaymentIntentId(99L)).thenReturn(Optional.of(order));
    }

    private static PaymentIntentFailedEvent failed(PaymentPurpose purpose, PaymentFailureCategory category) {
        return new PaymentIntentFailedEvent(99L, "pi", 7L, purpose, PayChannel.QRIS, 10_000, "IDR", category);
    }

    @Test
    void expiredBecomesExpired() {
        handler.onFailed(failed(PaymentPurpose.GIFT, PaymentFailureCategory.EXPIRED));
        assertThat(order.getStatus()).isEqualTo(GiftOrderStatus.EXPIRED);
        verify(orders).save(order);
    }

    @Test
    void declinedAndCancelledBecomeFailed() {
        handler.onFailed(failed(PaymentPurpose.GIFT, PaymentFailureCategory.GATEWAY_DECLINED));
        assertThat(order.getStatus()).isEqualTo(GiftOrderStatus.FAILED);

        GiftOrder second = GiftTestFixtures.order(52L, PayChannel.QRIS, 1, 10_000);
        second.attachPaymentIntent(98L);
        when(orders.findByPaymentIntentId(98L)).thenReturn(Optional.of(second));
        handler.onFailed(new PaymentIntentFailedEvent(98L, "pi2", 7L, PaymentPurpose.GIFT, PayChannel.QRIS, 10_000,
                "IDR", PaymentFailureCategory.USER_CANCELLED));
        assertThat(second.getStatus()).isEqualTo(GiftOrderStatus.FAILED);
    }

    @Test
    void nonPendingIsUntouched() {
        ReflectionTestUtils.setField(order, "status", GiftOrderStatus.DELIVERED);
        handler.onFailed(failed(PaymentPurpose.GIFT, PaymentFailureCategory.EXPIRED));
        assertThat(order.getStatus()).isEqualTo(GiftOrderStatus.DELIVERED);
        verify(orders, never()).save(any());
    }

    @Test
    void nonGiftOrNullCategoryIsSkipped() {
        handler.onFailed(failed(PaymentPurpose.TAILSONALITY, PaymentFailureCategory.EXPIRED));
        handler.onFailed(failed(PaymentPurpose.GIFT, null));
        verify(orders, never()).findByPaymentIntentId(anyLong());
        verifyNoInteractions(orders);
    }

    @Test
    void orderEntityWritesOnlyDirtyColumns() {
        // 本监听 / 重放用实体 save 置 EXPIRED / FAILED：全列 UPDATE 会把装载时的旧 pet_id 写回（宠物已被并发删除 → 撞外键、
        // 过期扫描整批回滚），也会覆盖交付条件更新写入的 delivered_at / undelivered_reason。
        assertThat(GiftOrder.class.isAnnotationPresent(org.hibernate.annotations.DynamicUpdate.class)).isTrue();
    }
}
