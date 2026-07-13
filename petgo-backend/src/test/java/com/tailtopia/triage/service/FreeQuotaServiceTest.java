package com.tailtopia.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.triage.TriageProperties;
import com.tailtopia.triage.domain.UserMonthlyFreeQuota;
import com.tailtopia.triage.dto.FreeQuotaView;
import com.tailtopia.triage.repository.UserMonthlyFreeQuotaRepository;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0（纯单测，mock repo/props，无 DB）。验 {@link FreeQuotaService} 的 clamp、WIB period、
 * tryConsume 分支（limit<=0 短路 / insertIfAbsent 再原子扣 / 按返回值映射）、status 组装。
 * 与 service 同包以直接测 package-private {@code limit()}/{@code currentPeriod()}。
 */
@ExtendWith(MockitoExtension.class)
class FreeQuotaServiceTest {

    @Mock
    private UserMonthlyFreeQuotaRepository quotas;
    @Mock
    private TriageProperties props;

    private FreeQuotaService svc() {
        return new FreeQuotaService(quotas, props);
    }

    // ---- AC4：clamp [0,35] ----

    @Test
    void limitClampsBelowZeroToZero() {
        when(props.getDefaultFreeQuota()).thenReturn(-1);
        assertThat(svc().limit()).isEqualTo(0);
    }

    @Test
    void limitPassesThroughInRange() {
        when(props.getDefaultFreeQuota()).thenReturn(1);
        assertThat(svc().limit()).isEqualTo(1);
    }

    @Test
    void limitClampsAboveMaxTo35() {
        when(props.getDefaultFreeQuota()).thenReturn(99);
        assertThat(svc().limit()).isEqualTo(35);
    }

    // ---- AC3：period 用 WIB ----

    @Test
    void currentPeriodUsesWibYearMonth() {
        String expected = YearMonth.now(ZoneId.of("Asia/Jakarta")).toString();
        assertThat(svc().currentPeriod()).isEqualTo(expected).matches("\\d{4}-\\d{2}");
    }

    // ---- AC2：tryConsume 分支 ----

    @Test
    void tryConsumeShortCircuitsWhenLimitZeroAndTouchesNoRepo() {
        when(props.getDefaultFreeQuota()).thenReturn(0);
        assertThat(svc().tryConsume(42L)).isFalse();
        verifyNoInteractions(quotas);
    }

    @Test
    void tryConsumeInsertsThenAtomicIncrementAndReturnsTrueOnSuccess() {
        when(props.getDefaultFreeQuota()).thenReturn(1);
        when(quotas.tryConsume(eq(42L), anyString(), eq(1))).thenReturn(1);
        assertThat(svc().tryConsume(42L)).isTrue();
        verify(quotas).insertIfAbsent(eq(42L), anyString());
        verify(quotas).tryConsume(eq(42L), anyString(), eq(1));
    }

    @Test
    void tryConsumeReturnsFalseWhenAtomicUpdateHitsZeroRows() {
        when(props.getDefaultFreeQuota()).thenReturn(1);
        when(quotas.tryConsume(eq(42L), anyString(), eq(1))).thenReturn(0);
        assertThat(svc().tryConsume(42L)).isFalse();
        verify(quotas).insertIfAbsent(eq(42L), anyString());
    }

    // ---- AC5：status 只读组装 remaining=max(0,limit-used) ----

    @Test
    void statusReflectsRemainingWhenPartiallyUsed() {
        when(props.getDefaultFreeQuota()).thenReturn(3);
        UserMonthlyFreeQuota row = usedRow(2);
        when(quotas.findByUserIdAndPeriod(eq(42L), anyString())).thenReturn(Optional.of(row));
        FreeQuotaView v = svc().status(42L);
        assertThat(v.limit()).isEqualTo(3);
        assertThat(v.used()).isEqualTo(2);
        assertThat(v.remaining()).isEqualTo(1);
        assertThat(v.period()).matches("\\d{4}-\\d{2}");
        verify(quotas, never()).insertIfAbsent(anyLong(), anyString());
    }

    @Test
    void statusRemainingNeverNegativeWhenUsedExceedsLimit() {
        // 例如后台把 limit 从 3 调到 1 后，历史 used=2 > limit：remaining 夹到 0 不为负。
        when(props.getDefaultFreeQuota()).thenReturn(1);
        when(quotas.findByUserIdAndPeriod(eq(42L), anyString())).thenReturn(Optional.of(usedRow(2)));
        assertThat(svc().status(42L).remaining()).isEqualTo(0);
    }

    @Test
    void statusUsesZeroWhenNoRowThisPeriod() {
        when(props.getDefaultFreeQuota()).thenReturn(1);
        when(quotas.findByUserIdAndPeriod(eq(42L), anyString())).thenReturn(Optional.empty());
        FreeQuotaView v = svc().status(42L);
        assertThat(v.used()).isEqualTo(0);
        assertThat(v.remaining()).isEqualTo(1);
    }

    // ---- hasFreeQuota ----

    @Test
    void hasFreeQuotaTrueWhenUsedBelowLimit() {
        when(props.getDefaultFreeQuota()).thenReturn(1);
        when(quotas.findByUserIdAndPeriod(eq(42L), anyString())).thenReturn(Optional.empty());
        assertThat(svc().hasFreeQuota(42L)).isTrue();
    }

    @Test
    void hasFreeQuotaFalseWhenExhausted() {
        when(props.getDefaultFreeQuota()).thenReturn(1);
        when(quotas.findByUserIdAndPeriod(eq(42L), anyString())).thenReturn(Optional.of(usedRow(1)));
        assertThat(svc().hasFreeQuota(42L)).isFalse();
    }

    /** 造一个 used_count=n 的只读行（实体无 setter，用反射填字段）。 */
    private static UserMonthlyFreeQuota usedRow(int n) {
        try {
            var ctor = UserMonthlyFreeQuota.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            UserMonthlyFreeQuota row = ctor.newInstance();
            var f = UserMonthlyFreeQuota.class.getDeclaredField("usedCount");
            f.setAccessible(true);
            f.setInt(row, n);
            return row;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
