package com.tailtopia.notify.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.profile.dto.PetProfileSnapshot;
import com.tailtopia.profile.service.PetProfileQueryService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/** L0：定时推送两个日扫（V1.5.0 onboarding-recall Story 5.2 · AC1 / AC3）—— WIB today、各自只跑一类、纪念日开关、cron 默认值。 */
class ScheduledPushJobTest {

    /** UTC 11-14 20:00 = WIB 11-15 03:00 → today 必须是 WIB 11-15。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T20:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate WIB_TODAY = LocalDate.of(2026, 11, 15);

    private final PetProfileQueryService query = mock(PetProfileQueryService.class);
    private final ScheduledPushPlanner planner = mock(ScheduledPushPlanner.class);
    private final ScheduledPushDispatcher dispatcher = mock(ScheduledPushDispatcher.class);
    private final List<PetProfileSnapshot> snaps = List.of(new PetProfileSnapshot(1, 10, "Mochi", null, WIB_TODAY));

    private ScheduledPushJob job(boolean anniversaryEnabled) {
        when(query.allSnapshots()).thenReturn(snaps);
        return new ScheduledPushJob(query, planner, dispatcher, anniversaryEnabled, CLOCK);
    }

    @Test
    @DisplayName("生日日扫：只调 planBirthdays（WIB today），逐条派发")
    void birthdayScan() {
        PlannedPush p = new PlannedPush(1, 10, NotificationType.PET_BIRTHDAY, "2026", "Mochi", 2);
        when(planner.planBirthdays(WIB_TODAY, snaps, Set.of())).thenReturn(List.of(p));
        job(true).runBirthdayScan();
        verify(dispatcher).dispatch(p);
        verify(planner, never()).planAnniversaries(any(), anyList(), any());
        verify(planner, never()).plan(any(), anyList(), any());
    }

    @Test
    @DisplayName("纪念日日扫：只调 planAnniversaries（WIB today）")
    void anniversaryScan() {
        PlannedPush p = new PlannedPush(1, 10, NotificationType.COMPANION_ANNIVERSARY, "30", "Mochi", 30, false);
        when(planner.planAnniversaries(WIB_TODAY, snaps, Set.of())).thenReturn(List.of(p));
        job(true).runAnniversaryScan();
        verify(dispatcher).dispatch(p);
        verify(planner, never()).planBirthdays(any(), anyList(), any());
    }

    @Test
    @DisplayName("AC3：纪念日开关关闭 → no-op（连快照都不取）")
    void anniversaryDisabled() {
        ScheduledPushJob j = new ScheduledPushJob(query, planner, dispatcher, false, CLOCK);
        j.runAnniversaryScan();
        verifyNoInteractions(query, planner, dispatcher);
        assertThat(j.today()).isEqualTo(WIB_TODAY);
    }

    @Test
    @DisplayName("AC1：cron 默认 11:00 WIB（04:00 UTC）与 19:00 WIB（12:00 UTC），zone=UTC")
    void cronDefaults() throws Exception {
        Method b = ScheduledPushJob.class.getMethod("runBirthdayScan");
        Method a = ScheduledPushJob.class.getMethod("runAnniversaryScan");
        assertThat(b.getAnnotation(Scheduled.class).cron())
                .isEqualTo("${petgo.scheduled-push.birthday-cron:0 0 4 * * *}");
        assertThat(a.getAnnotation(Scheduled.class).cron())
                .isEqualTo("${petgo.scheduled-push.anniversary-cron:0 0 12 * * *}");
        assertThat(b.getAnnotation(Scheduled.class).zone()).isEqualTo("UTC");
        assertThat(a.getAnnotation(Scheduled.class).zone()).isEqualTo("UTC");
        assertThat(java.util.Arrays.stream(ScheduledPushJob.class.getMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))).hasSize(2);
    }

    @Test
    @DisplayName("AC1：旧键 petgo.scheduled-push.cron 已从 application.yml 移除，新三键在")
    void ymlKeys() throws Exception {
        String yml = new String(getClass().getResourceAsStream("/application.yml").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(yml).doesNotContain("    cron: ${SCHEDULED_PUSH")
                .contains("birthday-cron: ${BIRTHDAY_PUSH_CRON:0 0 4 * * *}")
                .contains("anniversary-cron: ${ANNIVERSARY_PUSH_CRON:0 0 12 * * *}")
                .contains("anniversary-enabled: ${ANNIVERSARY_PUSH_ENABLED:true}");
    }
}
