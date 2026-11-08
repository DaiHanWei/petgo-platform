package com.tailtopia.shop.logistics;

import static com.tailtopia.shop.logistics.LogisticsFixtures.NOW;
import static com.tailtopia.shop.logistics.LogisticsFixtures.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopPaymentAlerts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** L0：v1.4.2 shop Story 5.2 AC3 —— 停滞检测（72 小时边界、手工运单 / 终态不参与、锁、单笔失败、飞书汇总）。 */
class LogisticsStallJobTest {

    private static final Duration STALL = Duration.ofHours(72);
    private static final Instant RUN_AT = NOW.plus(Duration.ofDays(10));
    private static final Instant CUTOFF = RUN_AT.minus(STALL);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ShipmentRepository shipments = mock(ShipmentRepository.class);
    private final ShopOrderRepository orders = mock(ShopOrderRepository.class);
    private final ShopPaymentAlerts alerts = mock(ShopPaymentAlerts.class);
    private final PlatformTransactionManager txm = mock(PlatformTransactionManager.class);

    private LogisticsStallJob job;
    private ShopOrder order;

    @BeforeEach
    void setUp() {
        job = new LogisticsStallJob(jdbc, shipments, orders, alerts, txm, STALL,
                Clock.fixed(RUN_AT, ZoneOffset.UTC));
        when(jdbc.queryForObject(LogisticsStallJob.TRY_LOCK_SQL, Boolean.class)).thenReturn(true);
        order = LogisticsFixtures.paidOrder(false);
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private static Shipment biteshipAt(long id, String providerStatus, Instant at) {
        Shipment s = LogisticsFixtures.biteshipShipment(id, "bs_" + id);
        ReflectionTestUtils.setField(s, "providerStatus", providerStatus);
        ReflectionTestUtils.setField(s, "providerStatusAt", at);
        return s;
    }

    private void candidates(Long... ids) {
        when(shipments.findStallCandidateOrderIds(CUTOFF, LogisticsStallJob.BATCH)).thenReturn(List.of(ids));
    }

    @Test
    @DisplayName("超过 72 小时无新状态 → 标 STALLED（未确认）、用户侧不提示、飞书汇总一次（展示号）")
    void flagsStalled() {
        candidates(ORDER_ID);
        when(shipments.findActiveByShopOrderId(ORDER_ID))
                .thenReturn(List.of(biteshipAt(1L, "confirmed", CUTOFF.minusSeconds(1))));
        LogisticsStallJob.Result r = job.run();
        assertThat(r.skipped()).isFalse();
        assertThat(r.flagged()).containsExactly(LogisticsFixtures.DISPLAY_NO);
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.STALLED);
        assertThat(order.getLogisticsExceptionAt()).isEqualTo(RUN_AT);
        assertThat(order.isLogisticsExceptionConfirmed()).isFalse();
        assertThat(order.hasUserVisibleDeliveryIssue()).isFalse();
        verify(orders).save(order);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(alerts, times(1)).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS), text.capture());
        assertThat(text.getValue()).contains(LogisticsFixtures.DISPLAY_NO).contains("72").doesNotContain("Budi");
    }

    @Test
    @DisplayName("🔴 72 小时整 → 不标")
    void exactly72HoursNotFlagged() {
        candidates(ORDER_ID);
        when(shipments.findActiveByShopOrderId(ORDER_ID)).thenReturn(List.of(biteshipAt(1L, "in_transit", CUTOFF)));
        assertThat(job.run().flagged()).isEmpty();
        assertThat(order.getLogisticsException()).isNull();
        verify(orders, never()).save(any());
        verify(alerts, never()).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS), anyString());
    }

    @Test
    @DisplayName("复审 2：运营清除停滞后从 timer_resumed_at 重新计时——清除未满 72 小时不重标，满且超过才标")
    void timerResumedRestartsStall() {
        candidates(ORDER_ID);
        when(shipments.findActiveByShopOrderId(ORDER_ID))
                .thenReturn(List.of(biteshipAt(1L, "in_transit", CUTOFF.minus(Duration.ofDays(5)))));
        order.flagLogisticsException(LogisticsException.STALLED, NOW);
        order.clearLogisticsException(RUN_AT.minus(Duration.ofHours(1)));       // 一小时前运营清除
        assertThat(job.run().flagged()).isEmpty();
        assertThat(order.getLogisticsException()).isNull();

        order.clearLogisticsException(CUTOFF);                                  // 无标记，不改
        ReflectionTestUtils.setField(order, "timerResumedAt", CUTOFF);          // 恰好 72 小时整
        assertThat(job.run().flagged()).isEmpty();

        ReflectionTestUtils.setField(order, "timerResumedAt", CUTOFF.minusSeconds(1));
        assertThat(job.run().flagged()).containsExactly(LogisticsFixtures.DISPLAY_NO);
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.STALLED);
    }

    @Test
    @DisplayName("手工运单不参与（没有物流状态来源）")
    void manualIgnored() {
        candidates(ORDER_ID);
        Shipment m = Shipment.ship(ORDER_ID, Carrier.JNE, "JN1", 0L);
        ReflectionTestUtils.setField(m, "providerStatusAt", CUTOFF.minus(Duration.ofDays(5)));
        when(shipments.findActiveByShopOrderId(ORDER_ID)).thenReturn(List.of(m));
        assertThat(job.run().flagged()).isEmpty();
        assertThat(order.getLogisticsException()).isNull();
    }

    @Test
    @DisplayName("已有其他处置的 Biteship 状态（cancelled / rejected / disposed / returned / courier_not_found）不参与")
    void handledStatusesIgnored() {
        for (String ps : LogisticsStallJob.HANDLED_PROVIDER_STATUSES) {
            assertThat(LogisticsStallJob.isStalled(biteshipAt(1L, ps, CUTOFF.minusSeconds(60)), CUTOFF))
                    .as(ps).isFalse();
        }
        assertThat(LogisticsStallJob.isStalled(biteshipAt(1L, "on_hold", CUTOFF.minusSeconds(60)), CUTOFF)).isTrue();
    }

    @Test
    @DisplayName("订单已有异常 / 不在待发货 / 已发货 → 不标（重新校验）")
    void orderRecheck() {
        candidates(ORDER_ID);
        when(shipments.findActiveByShopOrderId(ORDER_ID))
                .thenReturn(List.of(biteshipAt(1L, "confirmed", CUTOFF.minusSeconds(1))));
        order.flagLogisticsException(LogisticsException.PICKUP_FAILED, NOW);
        assertThat(job.run().flagged()).isEmpty();
        assertThat(order.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
    }

    @Test
    @DisplayName("拿不到 advisory lock → 整轮跳过、不查候选")
    void lockHeldSkips() {
        when(jdbc.queryForObject(LogisticsStallJob.TRY_LOCK_SQL, Boolean.class)).thenReturn(false);
        LogisticsStallJob.Result r = job.run();
        assertThat(r.skipped()).isTrue();
        verify(shipments, never()).findStallCandidateOrderIds(any(), anyInt());
        verify(alerts, never()).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS), anyString());
    }

    @Test
    @DisplayName("单笔失败只 WARN，不中断其余；汇总只含成功标记的")
    void singleFailureContinues() {
        ShopOrder second = LogisticsFixtures.paidOrder(false);
        ReflectionTestUtils.setField(second, "id", 200L);
        ReflectionTestUtils.setField(second, "displayNo", "TOKO-20261105-ZZZ999");
        candidates(ORDER_ID, 200L);
        when(orders.findById(ORDER_ID)).thenThrow(new IllegalStateException("boom"));
        when(orders.findById(200L)).thenReturn(Optional.of(second));
        when(shipments.findActiveByShopOrderId(200L))
                .thenReturn(List.of(biteshipAt(2L, "confirmed", CUTOFF.minusSeconds(1))));
        LogisticsStallJob.Result r = job.run();
        assertThat(r.flagged()).containsExactly("TOKO-20261105-ZZZ999");
        assertThat(second.getLogisticsException()).isEqualTo(LogisticsException.STALLED);
        verify(alerts, times(1)).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS), anyString());
    }

    @Test
    @DisplayName("无候选 → 不发飞书")
    void noneNoAlert() {
        candidates();
        assertThat(job.run().flagged()).isEmpty();
        verify(alerts, never()).alert(org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.alert.OpsChannel.OPS), anyString());
    }

    @Test
    @DisplayName("汇总最多列 20 个展示号，其余写「等共 N 单」")
    void summaryCapped() {
        List<String> nos = new ArrayList<>();
        IntStream.range(0, 25).forEach(i -> nos.add("TOKO-" + i));
        String text = LogisticsStallJob.summary(nos, 72);
        assertThat(text).contains("TOKO-19").doesNotContain("TOKO-20、").doesNotContain("TOKO-24").contains("等共 25 单");
        assertThat(LogisticsStallJob.summary(List.of("TOKO-1"), 72)).doesNotContain("等共");
    }

    @Test
    @DisplayName("候选查询传入 cutoff = now − 配置小时数、上限 500")
    void cutoffFromConfig() {
        LogisticsStallJob custom = new LogisticsStallJob(jdbc, shipments, orders, alerts, txm, Duration.ofHours(48),
                Clock.fixed(RUN_AT, ZoneOffset.UTC));
        when(shipments.findStallCandidateOrderIds(any(), anyInt())).thenReturn(List.of());
        custom.run();
        verify(shipments).findStallCandidateOrderIds(eq(RUN_AT.minus(Duration.ofHours(48))), eq(500));
        verify(orders, never()).findById(anyLong());
    }
}
