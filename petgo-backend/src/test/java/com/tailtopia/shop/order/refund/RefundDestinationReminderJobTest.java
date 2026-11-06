package com.tailtopia.shop.order.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.order.pricing.RefundQuote;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/** L0：v1.4.2 shop Story 4.1 AC6.2 —— 7 天阈值、只提醒一次、不推送用户。 */
class RefundDestinationReminderJobTest {

    private static final Instant NOW = Instant.parse("2026-11-20T02:00:00Z");

    @Test
    @DisplayName("阈值 = now − 7 天、只取未提醒过的 AWAITING_DESTINATION；提醒后写 destination_reminded_at")
    void remindsOnceAfterSevenDays() {
        ShopOrderRefundRepository refunds = mock(ShopOrderRefundRepository.class);
        ShopOrderRepository orders = mock(ShopOrderRepository.class);
        ShopOrderRefund r = ShopOrderRefund.create("rf1", 5L, new RefundQuote(RefundScope.WHOLE, null, null,
                CancelReason.OUT_OF_STOCK, 100L, 0L, 0L, 100L, 0L, 100L, false), null, NOW.minusSeconds(8 * 86_400L));
        when(refunds.findByStatusAndCreatedAtBeforeAndDestinationRemindedAtIsNullOrderByCreatedAtAsc(
                ShopRefundStatus.AWAITING_DESTINATION, Instant.parse("2026-11-13T02:00:00Z"))).thenReturn(List.of(r));
        when(orders.findById(5L)).thenReturn(Optional.empty());

        int n = new RefundDestinationReminderJob(refunds, orders, Clock.fixed(NOW, ZoneOffset.UTC)).run();

        assertThat(n).isEqualTo(1);
        assertThat(r.getDestinationRemindedAt()).isEqualTo(NOW);
        verify(refunds).save(r);
    }

    @Test
    @DisplayName("每天 WIB 09:00；任务不依赖任何通知 / 推送组件")
    void scheduleAndNoPush() throws Exception {
        Method run = RefundDestinationReminderJob.class.getMethod("run");
        Scheduled s = run.getAnnotation(Scheduled.class);
        assertThat(s.cron()).isEqualTo("0 0 9 * * *");
        assertThat(s.zone()).isEqualTo("Asia/Jakarta");
        assertThat(Arrays.stream(RefundDestinationReminderJob.class.getDeclaredFields()).map(Field::getType)
                .map(Class::getName)).noneMatch(n -> n.contains("notify") || n.contains("Push"));
    }
}
