package com.tailtopia.shop.order.notify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0：登记「待提醒」的开关闸门（v1.3.0 shop-v2 复审 #14）。
 *
 * <p>🔴 <b>守的是「off→live 那天不会重播存量单」</b>。
 * 原实现只在扫描器那头判 {@code isLive()}，行却照样入队。于是运营在 OD-5 定下 receive_id、
 * 把 mode 切到 live 的那一天，几个月的存量订单会被当作新单、<b>从最旧的一条开始</b>
 * 一批批播报（{@code collectWindow} 按 {@code created_at} 升序取），持续几十分钟，
 * 而运营无法把重播和真的新订单区分开。那些历史单要么早发完了、要么已由别的渠道处理过，
 * 它们不是「待办」。
 */
@ExtendWith(MockitoExtension.class)
class ShopOrderNotifyEnqueueGateTest {

    @Mock
    private ShopOrderNotifyQueueRepository queue;

    @Mock
    private ShopOrderRepository orders;

    @Mock
    private ShopOrderLineRepository orderLines;

    private final ShopOrderNotifyProperties props = new ShopOrderNotifyProperties();

    private ShopOrderNotifyService service() {
        return new ShopOrderNotifyService(queue, orders, orderLines, props);
    }

    private void goLive() {
        props.setMode("live");
        props.setReceiveId("oc_test_chat_id");
    }

    @Test
    @DisplayName("🎯 mode=off 时一行都不入队 —— 攒下来的迟早会被当成新单重播")
    void offModeEnqueuesNothing() {
        // props 默认 mode=off

        service().enqueue(42L);

        // 🎯 删掉 enqueue() 开头的 isLive() 闸门，这条必须红。
        verifyNoInteractions(queue);
    }

    @Test
    @DisplayName("receive_id 未配（等同于没开）同样不入队")
    void missingReceiveIdEnqueuesNothing() {
        props.setMode("live");       // 只开了 mode，没给收件人 —— 发不出去

        service().enqueue(42L);

        verifyNoInteractions(queue);
    }

    @Test
    @DisplayName("mode=live 时正常入队，且重复到账只落一行（幂等不受本次改动影响）")
    void liveModeEnqueuesOnceAndStaysIdempotent() {
        goLive();
        when(queue.existsByShopOrderId(42L)).thenReturn(false, true);

        ShopOrderNotifyService s = service();
        s.enqueue(42L);
        s.enqueue(42L);      // 支付回调双通道：同一单的到账事件可能到两次

        verify(queue).save(any());
        verify(queue, never()).deleteById(anyLong());
    }
}
