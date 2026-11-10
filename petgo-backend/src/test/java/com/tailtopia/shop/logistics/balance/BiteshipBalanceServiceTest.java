package com.tailtopia.shop.logistics.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.logistics.biteship.BiteshipProperties;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（V1.4.2 shop-admin Story 4.1 · AC6 / AC7）：录入记入值与录入时预估、低于阈值按快照去重提醒 OPS。 */
class BiteshipBalanceServiceTest {

    private final BiteshipBalanceSnapshotRepository snapshots = mock(BiteshipBalanceSnapshotRepository.class);
    private final ShippingSettingsRepository settings = mock(ShippingSettingsRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final OpsAlertService ops = mock(OpsAlertService.class);
    private final ShippingSettings st = mock(ShippingSettings.class);
    private BiteshipBalanceService service;

    @BeforeEach
    void setUp() {
        service = new BiteshipBalanceService(snapshots, settings, jdbc, new BiteshipProperties(), audit, ops,
                Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneOffset.UTC));
        when(settings.findById(ShippingSettings.SINGLETON_ID)).thenReturn(Optional.of(st));
        when(snapshots.save(any())).thenAnswer(inv -> {
            BiteshipBalanceSnapshot s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 42L);
            return s;
        });
    }

    @SuppressWarnings("unchecked")
    private void waybillsSince(BiteshipBalanceEstimate.Waybill... w) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(w));
    }

    @Test
    void firstRecordHasNoEstimateBeforeAndAudits() {
        when(snapshots.findFirstByOrderByRecordedAtDescIdDesc()).thenReturn(Optional.empty());
        var saved = service.record(2_000_000, 7L);
        assertThat(saved.getAmount()).isEqualTo(2_000_000);
        assertThat(saved.getEstimateBefore()).isNull();
        assertThat(saved.getRecordedBy()).isEqualTo(7L);
        verify(audit).record(eq(7L), eq(AuditActions.SHOP_BITESHIP_BALANCE_RECORDED), eq("BITESHIP_BALANCE"), eq("42"),
                org.mockito.ArgumentMatchers.contains("未录入"));
    }

    @Test
    void reRecordStoresTheEstimateAtThatMoment() {
        var last = BiteshipBalanceSnapshot.record(100_000, null, 7L, Instant.parse("2026-11-01T03:00:00Z"));
        when(snapshots.findFirstByOrderByRecordedAtDescIdDesc()).thenReturn(Optional.of(last));
        waybillsSince(new BiteshipBalanceEstimate.Waybill(30_000, 0, false));
        var saved = service.record(500_000, 8L);
        assertThat(saved.getEstimateBefore()).isEqualTo(70_000L);
    }

    @Test
    void belowThresholdAlertsOpsDedupedBySnapshot() {
        var snap = BiteshipBalanceSnapshot.record(400_000, null, 7L, Instant.parse("2026-11-01T03:00:00Z"));
        ReflectionTestUtils.setField(snap, "id", 5L);
        when(snapshots.findFirstByOrderByRecordedAtDescIdDesc()).thenReturn(Optional.of(snap));
        when(st.getBalanceAlertThreshold()).thenReturn(500_000L);
        waybillsSince(new BiteshipBalanceEstimate.Waybill(88_000, 0, false));
        service.checkQuietly();
        verify(ops).send(eq(OpsChannel.OPS), eq("biteship-balance:5"),
                org.mockito.ArgumentMatchers.contains("Rp 312.000"));
    }

    @Test
    void zeroThresholdOrNeverRecordedDoesNotAlert() {
        when(snapshots.findFirstByOrderByRecordedAtDescIdDesc()).thenReturn(Optional.empty());
        service.checkQuietly();
        var snap = BiteshipBalanceSnapshot.record(1, null, 7L, Instant.parse("2026-11-01T03:00:00Z"));
        when(snapshots.findFirstByOrderByRecordedAtDescIdDesc()).thenReturn(Optional.of(snap));
        when(st.getBalanceAlertThreshold()).thenReturn(0L);
        waybillsSince(new BiteshipBalanceEstimate.Waybill(10, 0, false));
        service.checkQuietly();
        verify(ops, never()).send(any(), any(), any());
    }

    @Test
    void checkFailureNeverPropagates() {
        when(snapshots.findFirstByOrderByRecordedAtDescIdDesc()).thenThrow(new IllegalStateException("db down"));
        service.checkQuietly();
        verify(ops, never()).send(any(), any(), any());
    }

    @Test
    void negativeInputsAreRejected() {
        assertThatThrownBy(() -> service.record(-1, 7L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.setThreshold(-1, 7L)).isInstanceOf(AppException.class);
    }

    @Test
    void thresholdChangeAuditsOldToNew() {
        when(st.getBalanceAlertThreshold()).thenReturn(0L);
        when(snapshots.findFirstByOrderByRecordedAtDescIdDesc()).thenReturn(Optional.empty());
        service.setThreshold(500_000, 7L);
        verify(st).applyBalanceAlertThreshold(500_000);
        verify(audit).record(eq(7L), eq(AuditActions.SHOP_SHIPPING_SETTINGS_UPDATED), eq("SHIPPING_SETTINGS"), eq("1"),
                eq("Biteship 余额预警阈值：0 → 500000"));
    }
}
