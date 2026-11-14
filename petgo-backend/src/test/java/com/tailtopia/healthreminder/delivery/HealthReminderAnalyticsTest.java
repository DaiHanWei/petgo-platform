package com.tailtopia.healthreminder.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthNotification;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.repository.HealthNotificationRepository;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.healthreminder.schedule.SendGroup;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.HealthPushOutcome;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.support.PetFixtures;
import com.tailtopia.shared.analytics.AnalyticsClient;
import com.tailtopia.shared.analytics.AnalyticsDistinctId;
import com.tailtopia.shared.analytics.AnalyticsEventGuard;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（V1.5.0 Story 2.4 · AC3 / AC6）：投递 + 埋点，PRD AC58 / AC63（合并 / 跳过 / 顺延 / 重跑）；白名单登记。 */
class HealthReminderAnalyticsTest {

    private static final LocalDate DAY = LocalDate.parse("2027-03-01");

    private final AnalyticsClient client = mock(AnalyticsClient.class);
    private final PostHogHealthReminderAnalytics analytics = new PostHogHealthReminderAnalytics(client);

    private static HealthReminder row(long id, ReminderKind kind, PushPoint point) {
        HealthReminder r = HealthReminder.pending(20L, 7L, id + 100, kind, point, ReminderOrigin.RECORD, DAY, DAY);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    @Test
    void ac63MergedOneNotificationTwoReminderEventsSameNotificationId() {
        HealthNotificationRepository notifications = mock(HealthNotificationRepository.class);
        when(notifications.save(any())).thenAnswer(inv -> {
            HealthNotification n = inv.getArgument(0);
            ReflectionTestUtils.setField(n, "id", 50L);
            return n;
        });
        PetProfileRepository pets = mock(PetProfileRepository.class);
        when(pets.findById(20L)).thenReturn(Optional.of(PetFixtures.pet(7L, 20L, PetType.CAT, Instant.EPOCH)));
        NotificationService ns = mock(NotificationService.class);
        when(ns.sendHealthReminder(eq(7L), eq(NotificationType.HEALTH_VACCINE_D_DAY), anyString(), anyString(),
                anyString())).thenReturn(new HealthPushOutcome(900L, HealthPushOutcome.Result.SENT));
        AccountQueryService accounts = mock(AccountQueryService.class);
        when(accounts.localeOf(7L)).thenReturn(Locale.ENGLISH);
        ReloadableResourceBundleMessageSource m = new ReloadableResourceBundleMessageSource();
        m.setBasename("classpath:i18n/messages");
        m.setDefaultEncoding("UTF-8");
        m.setFallbackToSystemLocale(false);
        HealthReminderDispatcher dispatcher = new HealthReminderDispatcher(notifications,
                mock(HealthReminderRepository.class), mock(HealthRecordRepository.class), pets,
                new HealthCopyRenderer(m), ns, accounts, analytics,
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));

        HealthReminder a = row(1L, ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY);
        a.setSnapshot(VaccinePreset.F4, "F4 (Tetracat)", 2, null, null);
        a.deferTo(DAY); // 0
        HealthReminder b = row(2L, ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY);
        b.setSnapshot(VaccinePreset.RABIES, "Rabies", null, null, null);
        var out = dispatcher.dispatch(new SendGroup(20L, 7L, DAY, PushPoint.D_DAY, List.of(a, b)));
        assertThat(out.sent()).isTrue();
        assertThat(a.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(a.getHealthNotificationId()).isEqualTo(50L);

        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
        verify(client, times(3)).capture(eq(AnalyticsDistinctId.of(7L)), event.capture(), props.capture());
        assertThat(event.getAllValues()).containsExactly("health_notification_sent", "health_reminder_sent",
                "health_reminder_sent");
        Map<String, Object> sent = props.getAllValues().get(0);
        assertThat(sent).containsEntry("merged_count", 2).containsEntry("notification_kind", "VACCINE_D_DAY")
                .containsEntry("push_point", "d_day").containsEntry("push_result", "sent");
        Object token = sent.get("notification_id");
        assertThat(props.getAllValues().get(1)).containsEntry("notification_id", token).containsEntry("record_id", 101L);
        assertThat(props.getAllValues().get(2)).containsEntry("notification_id", token);

        // 重跑：行已 SENT → 什么都不发
        org.mockito.Mockito.clearInvocations(client, ns);
        assertThat(dispatcher.dispatch(new SendGroup(20L, 7L, DAY, PushPoint.D_DAY, List.of(a, b))).sent()).isFalse();
        verify(client, never()).capture(anyString(), anyString(), anyMap());
        verify(ns, never()).sendHealthReminder(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
    }

    @Test
    void ac63SkippedCancelledEventDeferredDaysOldAppSilent() {
        HealthReminder dew = row(3L, ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY);
        analytics.cancelled(dew, CancelReason.SKIPPED_CAP, "VACCINE_D_MINUS_7", null);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.forClass(Map.class);
        verify(client).capture(eq(AnalyticsDistinctId.of(7L)), eq("health_reminder_cancelled"), props.capture());
        assertThat(props.getValue()).containsEntry("reason", "skipped_cap")
                .containsEntry("displaced_by", "VACCINE_D_MINUS_7").containsEntry("reminder_kind",
                        "DEWORM_INTERNAL_D_DAY").doesNotContainKey("by_record_id");

        org.mockito.Mockito.clearInvocations(client);
        analytics.cancelled(dew, CancelReason.OLD_APP, null, null);
        verify(client, never()).capture(anyString(), anyString(), anyMap());

        HealthReminder moved = row(4L, ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7);
        moved.deferTo(DAY.plusDays(1));
        HealthNotification n = HealthNotification.create("tok", 7L, 20L, ReminderKind.VACCINE_D_MINUS_7,
                PushPoint.D_MINUS_7, ReminderOrigin.RECORD, 1, 1, HealthNotification.Route.PAGE, DAY.plusDays(1));
        analytics.reminderSent(moved, n);
        verify(client).capture(anyString(), eq("health_reminder_sent"), props.capture());
        assertThat(props.getValue()).containsEntry("deferred_days", 1);
    }

    @Test
    void eventsAndKeysAreWhitelisted() {
        AnalyticsEventGuard guard = new AnalyticsEventGuard();
        for (String e : List.of(PostHogHealthReminderAnalytics.EVENT_NOTIFICATION_SENT,
                PostHogHealthReminderAnalytics.EVENT_REMINDER_SENT,
                PostHogHealthReminderAnalytics.EVENT_REMINDER_CANCELLED)) {
            assertThat(guard.allowsEvent(e)).as(e).isTrue();
        }
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        for (String k : List.of("notification_id", "pet_id", "notification_kind", "reminder_kind", "push_point",
                "origin", "reason", "push_result", "merged_count", "deferred_days", "reminder_id", "record_id",
                "by_record_id", "displaced_by")) {
            raw.put(k, "x");
        }
        assertThat(guard.filterProperties(raw)).containsOnlyKeys(raw.keySet());
    }
}
