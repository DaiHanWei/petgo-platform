package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.profile.repository.UserNewbieMarkDao;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 onboarding-recall Story 2.1 · AC9 —— ⑦ 每日补记：两天窗口、已有标记跳过、单人失败不影响其余。 */
class NewbieWeekJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    @Test
    void 扫今天减8到减7的窗口并跳过已有标记() {
        AccountQueryService accounts = mock(AccountQueryService.class);
        UserNewbieMarkDao dao = mock(UserNewbieMarkDao.class);
        NewbieMarkService marks = mock(NewbieMarkService.class);
        when(accounts.realUserIdsRegisteredWibBetween(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8)))
                .thenReturn(List.of(1L, 2L, 3L));
        when(dao.usersHaving(anyCollection(), eq("ONE_WEEK"))).thenReturn(Set.of(2L));
        when(marks.markIfAbsent(1L, NewbieTask.ONE_WEEK)).thenThrow(new IllegalStateException("boom"));
        when(marks.markIfAbsent(3L, NewbieTask.ONE_WEEK)).thenReturn(true);

        int written = new NewbieWeekJob(accounts, dao, marks).runFor(TODAY);

        assertThat(written).isEqualTo(1);
        verify(marks, never()).markIfAbsent(2L, NewbieTask.ONE_WEEK);
        verify(marks).markIfAbsent(3L, NewbieTask.ONE_WEEK);
    }

    @Test
    void 调度时区是雅加达且默认0点05分() throws Exception {
        var m = NewbieWeekJob.class.getMethod("run");
        var s = m.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(s.zone()).isEqualTo("Asia/Jakarta");
        assertThat(s.cron()).isEqualTo("${petgo.newbie.week-cron:0 5 0 * * *}");
    }
}
