package com.tailtopia.notify.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.UserLifecycleSnapshot;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.port.LifecycleContextPort;
import com.tailtopia.notify.port.LifecycleContextPort.LifecycleContext;
import com.tailtopia.notify.repository.LifecyclePushMarkRepository;
import com.tailtopia.notify.service.AppVersionGate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：生命周期日扫（V1.5.0 onboarding-recall Story 6.1 · AC1 / AC2）—— today 按 WIB、候选按窗口收窄取、上下文一次批量、
 * 召回本 story 关闭、已推剔除后再套上限、{@code SKIPPED_ACTIVE} 不占上限、总开关关闭 no-op。
 */
class LifecyclePushJobTest {

    /** UTC 11-14 20:00 = WIB 11-15 03:00。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T20:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate WIB_TODAY = LocalDate.of(2026, 11, 15);

    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final LifecycleContextPort context = mock(LifecycleContextPort.class);
    private final LifecyclePushPlanner planner = mock(LifecyclePushPlanner.class);
    private final LifecyclePushDispatcher dispatcher = mock(LifecyclePushDispatcher.class);
    private final LifecyclePushMarkRepository marks = mock(LifecyclePushMarkRepository.class);
    private final AppVersionGate gate = mock(AppVersionGate.class);

    {
        // 默认：所有人都在新版
        when(gate.usersOnV150OrLater(any())).thenAnswer(inv -> new HashSet<>(inv.<Collection<Long>>getArgument(0)));
    }

    private LifecyclePushJob job(boolean enabled, int cap) {
        return job(enabled, false, cap);
    }

    private LifecyclePushJob job(boolean enabled, boolean winback, int cap) {
        LifecyclePushProperties props = new LifecyclePushProperties();
        props.setEnabled(enabled);
        props.setWinbackEnabled(winback);
        props.setDailyCap(cap);
        return new LifecyclePushJob(accounts, context, planner, dispatcher, props, marks, gate, CLOCK);
    }

    private static LifecyclePlannedPush p(long user, LifecycleVariant v) {
        return new LifecyclePlannedPush(user, NotificationType.LIFECYCLE_D1, "ONCE", v, "Mochi", LifecycleLayer.C,
                "RECORD", null, "CAT", 0);
    }

    @Test
    @DisplayName("WIB today → 窗口快照 + 一次上下文 + 召回关闭的选项（6-2 AC1：不为召回取全量）")
    void wibTodayAndBatchContext() {
        List<UserLifecycleSnapshot> users = List.of(new UserLifecycleSnapshot(1, WIB_TODAY.minusDays(1), null, 0),
                new UserLifecycleSnapshot(2, WIB_TODAY.minusDays(3), null, 0));
        when(accounts.lifecycleSnapshots(WIB_TODAY)).thenReturn(users);
        when(context.contextOf(List.of(1L, 2L), WIB_TODAY)).thenReturn(LifecycleContext.EMPTY);
        when(planner.plan(eq(WIB_TODAY), eq(users), eq(LifecycleContext.EMPTY), any())).thenReturn(List.of());

        job(true, 10).runDailyScan();

        ArgumentCaptor<LifecyclePlanOptions> opts = ArgumentCaptor.forClass(LifecyclePlanOptions.class);
        verify(planner).plan(eq(WIB_TODAY), eq(users), eq(LifecycleContext.EMPTY), opts.capture());
        assertThat(opts.getValue().winbackEnabled()).isFalse();
        verify(accounts, never()).lifecycleSnapshots();
    }

    @Test
    @DisplayName("已推剔除后再套上限；SKIPPED_ACTIVE 照派发（写 mark）但不占上限")
    void capAndSkipped() {
        when(accounts.lifecycleSnapshots(WIB_TODAY)).thenReturn(List.of());
        when(context.contextOf(anyList(), any())).thenReturn(LifecycleContext.EMPTY);
        LifecyclePlannedPush already = p(1, LifecycleVariant.RECORD);
        LifecyclePlannedPush skipped = p(2, LifecycleVariant.SKIPPED_ACTIVE);
        LifecyclePlannedPush a = p(3, LifecycleVariant.RECORD);
        LifecyclePlannedPush b = p(4, LifecycleVariant.RECORD);
        when(planner.plan(any(), anyList(), any(), any())).thenReturn(List.of(already, skipped, a, b));
        when(marks.existsByUserIdAndPushKindAndNodeKey(1L, "LIFECYCLE_D1", "ONCE")).thenReturn(true);

        job(true, 1).runDailyScan();

        verify(dispatcher, never()).dispatch(already);
        verify(dispatcher).dispatch(skipped);
        verify(dispatcher).dispatch(a);
        verify(dispatcher, never()).dispatch(b);
        verify(dispatcher, times(2)).dispatch(any());
        verify(marks, times(4)).existsByUserIdAndPushKindAndNodeKey(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("6-2 AC1：召回开 → 取全量快照、选项 winbackEnabled=true")
    void winbackOnFetchesAll() {
        when(accounts.lifecycleSnapshots()).thenReturn(List.of());
        when(context.contextOf(anyList(), any())).thenReturn(LifecycleContext.EMPTY);
        when(planner.plan(any(), anyList(), any(), any())).thenReturn(List.of());

        job(true, true, 10).runDailyScan();

        ArgumentCaptor<LifecyclePlanOptions> opts = ArgumentCaptor.forClass(LifecyclePlanOptions.class);
        verify(planner).plan(eq(WIB_TODAY), anyList(), any(), opts.capture());
        assertThat(opts.getValue().winbackEnabled()).isTrue();
        assertThat(opts.getValue().winbackAfterDays()).isEqualTo(7);
        verify(accounts, never()).lifecycleSnapshots(any());
    }

    @Test
    @DisplayName("6-2 AC3：版本门槛一次批量；旧版用户不派发（不写 mark / 通知），SKIPPED_ACTIVE 也只对新版派发；旧版不占上限")
    void versionGate() {
        when(accounts.lifecycleSnapshots(WIB_TODAY)).thenReturn(List.of());
        when(context.contextOf(anyList(), any())).thenReturn(LifecycleContext.EMPTY);
        LifecyclePlannedPush oldApp = p(1, LifecycleVariant.RECORD);
        LifecyclePlannedPush oldAppSkipped = p(2, LifecycleVariant.SKIPPED_ACTIVE);
        LifecyclePlannedPush newApp = p(3, LifecycleVariant.RECORD);
        when(planner.plan(any(), anyList(), any(), any())).thenReturn(List.of(oldApp, oldAppSkipped, newApp));
        org.mockito.Mockito.doReturn(java.util.Set.of(3L)).when(gate).usersOnV150OrLater(any());

        job(true, 1).runDailyScan();

        verify(gate, times(1)).usersOnV150OrLater(List.of(1L, 2L, 3L));
        verify(dispatcher, never()).dispatch(oldApp);
        verify(dispatcher, never()).dispatch(oldAppSkipped);
        verify(dispatcher).dispatch(newApp);
    }

    @Test
    @DisplayName("6-2 AC2：截断顺序按计划器排序（D1 先于 D7），上限之外的不派发")
    void capKeepsMostUrgent() {
        when(accounts.lifecycleSnapshots(WIB_TODAY)).thenReturn(List.of());
        when(context.contextOf(anyList(), any())).thenReturn(LifecycleContext.EMPTY);
        LifecyclePlannedPush d1 = p(1, LifecycleVariant.RECORD);
        LifecyclePlannedPush d7 = new LifecyclePlannedPush(2, NotificationType.LIFECYCLE_D7, "ONCE",
                LifecycleVariant.NEWBIE_TASKS, null, LifecycleLayer.A, "TASKS_LEFT", null, null, 3);
        when(planner.plan(any(), anyList(), any(), any())).thenReturn(List.of(d1, d7));

        job(true, 1).runDailyScan();

        verify(dispatcher).dispatch(d1);
        verify(dispatcher, never()).dispatch(d7);
    }

    @Test
    @DisplayName("总开关关闭 → no-op")
    void disabled() {
        job(false, 10).runDailyScan();
        verifyNoInteractions(accounts, context, planner, dispatcher, marks, gate);
    }
}
