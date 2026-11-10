package com.tailtopia.shop.order.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.shop.order.event.ShopOrderReorderedEvent;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** L0：v1.4.2 shop Story 4.2 AC5 —— 重新下单通知监听。 */
class ShopOrderReorderedNotifyListenerTest {

    private static final ShopOrderReorderedEvent EVENT =
            new ShopOrderReorderedEvent(10L, 20L, 7L, "new-tok", "TOKO-20261105-NEW001");

    @Test
    @DisplayName("写库监听纪律：AFTER_COMMIT + REQUIRES_NEW（回滚则不发）")
    void annotations() throws NoSuchMethodException {
        Method m = ShopOrderReorderedNotifyListener.class.getMethod("onReordered", ShopOrderReorderedEvent.class);
        assertThat(m.getAnnotation(TransactionalEventListener.class).phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(m.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("sendWithCopy：copyKey=SHOP_ORDER_REORDERED、args=[新单展示号]、targetRef=新单 token、兜底为印尼语 F22")
    @SuppressWarnings("unchecked")
    void sendsWithCopy() {
        NotificationService ns = mock(NotificationService.class);
        new ShopOrderReorderedNotifyListener(ns).onReordered(EVENT);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(ns).sendWithCopy(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(NotificationType.SHOP_ORDER_REORDERED),
                org.mockito.ArgumentMatchers.eq("SHOP_ORDER_REORDERED"), args.capture(),
                org.mockito.ArgumentMatchers.eq("Pesananmu sudah dibuat ulang"),
                org.mockito.ArgumentMatchers.eq(
                        "Pembayaranmu sudah kami terima. Pesanan TOKO-20261105-NEW001 sedang disiapkan."),
                org.mockito.ArgumentMatchers.eq("new-tok"));
        assertThat(args.getValue()).containsExactly("TOKO-20261105-NEW001");
    }

    @Test
    @DisplayName("发送异常被吞（只 WARN），不影响已提交的重下单")
    void sendFailureSwallowed() {
        NotificationService ns = mock(NotificationService.class);
        when(ns.sendWithCopy(anyLong(), any(), anyString(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("push down"));
        assertThatCode(() -> new ShopOrderReorderedNotifyListener(ns).onReordered(EVENT)).doesNotThrowAnyException();
    }
}
