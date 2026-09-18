package com.tailtopia.shop.order.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0：扫描器的开关与失败语义（Story 3-4 AC3 / AC4 / AC6 / AC7）。
 *
 * <p>🔴 本类守的核心是 <b>「默认配置下一个出网请求都不发」</b> —— CI 与本地跑全量测试时，
 * 这个 {@code @Scheduled} 任务不得打到 Lark。AC6 把它标成 L1，
 * 但用 mock 在 L0 就能钉死「根本没调用客户端」，比等到 L1 更早拦住。
 */
@ExtendWith(MockitoExtension.class)
class ShopOrderNotifyScannerTest {

    @Mock
    private ShopOrderNotifyService notify;

    @Mock
    private LarkMessageClient lark;

    private final ShopOrderNotifyProperties props = new ShopOrderNotifyProperties();

    private ShopOrderNotifyScanner scanner() {
        return new ShopOrderNotifyScanner(notify, lark, props);
    }

    private void goLive() {
        props.setMode("live");
        props.setReceiveId("oc_test_chat_id");
    }

    @Test
    @DisplayName("🔴 默认 mode=off → 连队列都不查，零出网")
    void offModeDoesNothingAtAll() {
        scanner().scan();

        verifyNoInteractions(lark);
        verifyNoInteractions(notify);
    }

    @Test
    @DisplayName("🔴 mode=live 但收件人没配 → 同样零出网")
    void liveWithoutReceiverDoesNothingAtAll() {
        props.setMode("live");

        scanner().scan();

        verifyNoInteractions(lark);
        verifyNoInteractions(notify);
    }

    @Test
    @DisplayName("🔴 空窗口不发空消息（AC3 末条）")
    void emptyWindowSendsNothing() {
        goLive();
        when(notify.collectWindow()).thenReturn(Optional.empty());

        scanner().scan();

        verifyNoInteractions(lark);
        verify(notify, never()).markSent(org.mockito.ArgumentMatchers.anyList());
        verify(notify, never()).markFailed(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("一个窗口的三笔订单发**一条**消息，成功后整批回写 SENT")
    void oneWindowSendsExactlyOneMessage() {
        goLive();
        var batch = new ShopOrderNotifyService.Batch(List.of(1L, 2L, 3L),
                List.of(new ShopOrderNotifyMessage.Line("TOKO-A", 1000L, 1),
                        new ShopOrderNotifyMessage.Line("TOKO-B", 2000L, 2),
                        new ShopOrderNotifyMessage.Line("TOKO-C", 3000L, 3)));
        when(notify.collectWindow()).thenReturn(Optional.of(batch));

        scanner().scan();

        // 三笔 → 恰好一次 sendText（不是三次）
        verify(lark).sendText(anyString());
        verify(notify).markSent(List.of(1L, 2L, 3L));
        verify(notify, never()).markFailed(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("🔴 发送失败 → 只累加重试，绝不回写 SENT（AC4/AC7）")
    void sendFailureOnlyTouchesTheQueue() {
        goLive();
        var batch = new ShopOrderNotifyService.Batch(List.of(7L),
                List.of(new ShopOrderNotifyMessage.Line("TOKO-A", 1000L, 1)));
        when(notify.collectWindow()).thenReturn(Optional.of(batch));
        org.mockito.Mockito.doThrow(new IllegalStateException("lark 503"))
                .when(lark).sendText(anyString());

        // 🔴 异常绝不能冒出扫描器 —— @Scheduled 抛出去会让这一轮之后什么都不做
        scanner().scan();

        verify(notify).markFailed(List.of(7L));
        verify(notify, never()).markSent(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("收拢阶段炸了也不冒泡，且不会误判成发送失败")
    void collectFailureDoesNotCountAsSendFailure() {
        goLive();
        when(notify.collectWindow()).thenThrow(new IllegalStateException("db down"));

        scanner().scan();

        verifyNoInteractions(lark);
        verify(notify, never()).markFailed(org.mockito.ArgumentMatchers.anyList());
    }

    // ---------- 夜间静默（AC8：默认关闭） ----------

    @Test
    @DisplayName("🔴 夜间静默默认关闭 —— OD-5 定值前任何时刻都不静默")
    void quietHoursOffByDefault() {
        assertThat(scanner().isQuietNow()).isFalse();
    }

    @Test
    @DisplayName("🔴 静默窗口跨午夜（22:00–08:00）：夜里静默、白天不静默，逐小时钉死")
    void quietWindowSpansMidnight() {
        props.setQuietHoursEnabled(true);
        props.setQuietStartHour(22);
        props.setQuietEndHour(8);
        ShopOrderNotifyScanner s = scanner();

        // 22 > 8：若写成 start <= hour && hour < end，下面这些全会变成 false ——
        // 开关打开了却永远不静默，而且不报任何错。
        for (int h : new int[] {22, 23, 0, 3, 7}) {
            assertThat(s.isQuietAt(h)).as("%02d:00 应在夜间静默窗口内", h).isTrue();
        }
        for (int h : new int[] {8, 9, 12, 18, 21}) {
            assertThat(s.isQuietAt(h)).as("%02d:00 是运营在岗时段，必须照常发", h).isFalse();
        }
    }

    @Test
    @DisplayName("同日静默窗口（01:00–05:00）走「与」分支")
    void quietWindowWithinSameDay() {
        props.setQuietHoursEnabled(true);
        props.setQuietStartHour(1);
        props.setQuietEndHour(5);
        ShopOrderNotifyScanner s = scanner();

        assertThat(s.isQuietAt(0)).isFalse();
        assertThat(s.isQuietAt(1)).as("起始小时含").isTrue();
        assertThat(s.isQuietAt(4)).isTrue();
        assertThat(s.isQuietAt(5)).as("结束小时不含").isFalse();
        assertThat(s.isQuietAt(12)).isFalse();
    }

    @Test
    @DisplayName("开关关闭时，任何小时都不静默（哪怕窗口配得再宽）")
    void disabledBeatsAnyWindow() {
        props.setQuietStartHour(0);
        props.setQuietEndHour(23);
        ShopOrderNotifyScanner s = scanner();

        for (int h = 0; h < 24; h++) {
            assertThat(s.isQuietAt(h)).isFalse();
        }
    }

    // ================================================================
    // v1.3.0 shop-v2 复审 #15：FAILED 不是不可恢复的终态
    // ================================================================

    @Test
    @DisplayName("🎯 每轮先把冷却期已过的 FAILED 行放回队列，再收拢本轮")
    void requeuesCooledDownFailuresBeforeCollecting() {
        goLive();
        when(notify.collectWindow()).thenReturn(Optional.empty());

        scanner().scan();

        // 🎯 删掉 scan() 里的 safely(notify::requeueFailed, ...)，这条必须红。
        //    没有它，一次约 15 分钟的 Lark 故障（5 分钟一轮 × 3 次重试）就会把那段时间的
        //    订单永久判死 —— collectWindow 只查 PENDING，全仓没有任何其它重入队路径，
        //    事后把 receive_id 改对也救不回来。而这条提醒是通知仓库发货的唯一信号。
        verify(notify).requeueFailed();
    }

    @Test
    @DisplayName("重入队自身失败不得中断本轮投递（它只是尽力而为的一步）")
    void requeueFailureDoesNotAbortTheRound() {
        goLive();
        when(notify.requeueFailed()).thenThrow(new IllegalStateException("db hiccup"));
        when(notify.collectWindow()).thenReturn(Optional.empty());

        scanner().scan();       // 不抛

        verify(notify).collectWindow();
    }

    @Test
    @DisplayName("mode=off 时连重入队都不做 —— 关掉的功能不该有任何副作用")
    void offModeDoesNotEvenRequeue() {
        // props 默认 mode=off
        scanner().scan();

        verifyNoInteractions(notify);
        verifyNoInteractions(lark);
    }
}
