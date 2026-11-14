package com.tailtopia.notify.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.repository.FollowUpdatePushDao;
import com.tailtopia.notify.repository.PetDiaryNotifyLogDao;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** L0：追更日提醒去重日志清理（V1.4.2 social-topic · Story 1.2 · AC4.4）—— 截止日 = WIB 今天 − 30，失败只记日志。 */
class PetDiaryNotifyLogCleanupJobTest {

    @Test
    void cutoffIsWibTodayMinus30() {
        PetDiaryNotifyLogDao dao = mock(PetDiaryNotifyLogDao.class);
        FollowUpdatePushDao followDao = mock(FollowUpdatePushDao.class);
        // UTC 11-04 18:00 = WIB 11-05 01:00 → WIB 今天是 11-05
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T18:00:00Z"), ZoneOffset.UTC);
        PetDiaryNotifyLogCleanupJob job = new PetDiaryNotifyLogCleanupJob(dao, followDao, clock);

        assertThat(job.cutoff()).isEqualTo(LocalDate.of(2026, 10, 6));
        job.cleanup();
        verify(dao).deleteBefore(LocalDate.of(2026, 10, 6));
        // V1.5.0 Story 5.1 · AC12：关注更新推送三表同一截止日
        verify(followDao).deleteBefore(LocalDate.of(2026, 10, 6));
    }

    @Test
    void failureIsSwallowed() {
        PetDiaryNotifyLogDao dao = mock(PetDiaryNotifyLogDao.class);
        FollowUpdatePushDao followDao = mock(FollowUpdatePushDao.class);
        when(dao.deleteBefore(any())).thenThrow(new IllegalStateException("db down"));
        new PetDiaryNotifyLogCleanupJob(dao, followDao,
                Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC))
                .cleanup(); // 不抛
        // 一组失败不影响另一组
        verify(followDao).deleteBefore(any());
    }

    @Test
    void followUpdateCleanupFailureIsSwallowed() {
        PetDiaryNotifyLogDao dao = mock(PetDiaryNotifyLogDao.class);
        FollowUpdatePushDao followDao = mock(FollowUpdatePushDao.class);
        when(followDao.deleteBefore(any())).thenThrow(new IllegalStateException("db down"));
        new PetDiaryNotifyLogCleanupJob(dao, followDao,
                Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC)).cleanup(); // 不抛
        verify(dao).deleteBefore(any());
    }
}
