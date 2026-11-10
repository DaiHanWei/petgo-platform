package com.tailtopia.shop.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shop.coupon.repository.CouponExpiryNotifyLogDao;
import com.tailtopia.shop.coupon.repository.UserCouponRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** L0：v1.4.2 shop Story 2.3 AC5.2 ~ AC5.4 / AC5.10 —— {@link CouponExpiryReminderJob} 的锁 / 分批 / 收尾（Mockito）。 */
class CouponExpiryReminderJobTest {

    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");
    private static final Instant UNTIL = NOW.plus(Duration.ofHours(48));

    private final UserCouponRepository userCoupons = mock(UserCouponRepository.class);
    private final CouponExpiryNotifyLogDao logDao = mock(CouponExpiryNotifyLogDao.class);
    private final CouponExpiryReminderService reminder = mock(CouponExpiryReminderService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final CouponExpiryReminderJob job = new CouponExpiryReminderJob(userCoupons, logDao, reminder, jdbc,
            txManager, Clock.fixed(NOW, ZoneOffset.UTC));

    {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void 拿不到锁_跳过本轮() {
        when(jdbc.queryForObject(CouponExpiryReminderJob.TRY_LOCK_SQL, Boolean.class)).thenReturn(false);
        assertThat(job.run(NOW).skipped()).isTrue();
        verify(userCoupons, never()).findExpiryReminderUserIds(any(), any(), anyLong(), anyInt());
        verify(logDao, never()).deleteBefore(any());
    }

    @Test
    void 分批keyset_一人失败不影响他人_收尾清理30天前日志() {
        when(jdbc.queryForObject(CouponExpiryReminderJob.TRY_LOCK_SQL, Boolean.class)).thenReturn(true);
        List<Long> first = LongStream.rangeClosed(1, CouponExpiryReminderJob.BATCH).boxed().toList();
        when(userCoupons.findExpiryReminderUserIds(NOW, UNTIL, 0L, CouponExpiryReminderJob.BATCH)).thenReturn(first);
        when(userCoupons.findExpiryReminderUserIds(NOW, UNTIL, (long) CouponExpiryReminderJob.BATCH,
                CouponExpiryReminderJob.BATCH)).thenReturn(List.of(9001L, 9002L));
        when(reminder.remindUser(anyLong(), eq(NOW), eq(UNTIL))).thenReturn(CouponExpiryReminderService.Outcome.SENT);
        when(reminder.remindUser(eq(3L), eq(NOW), eq(UNTIL))).thenThrow(new IllegalStateException("db"));
        when(reminder.remindUser(eq(9002L), eq(NOW), eq(UNTIL)))
                .thenReturn(CouponExpiryReminderService.Outcome.SKIPPED);

        CouponExpiryReminderJob.Result r = job.run(NOW);

        assertThat(r.skipped()).isFalse();
        assertThat(r.candidates()).isEqualTo(CouponExpiryReminderJob.BATCH + 2);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.skippedUsers()).isEqualTo(1);
        assertThat(r.sent()).isEqualTo(CouponExpiryReminderJob.BATCH);
        verify(reminder).remindUser(9001L, NOW, UNTIL);
        verify(logDao).deleteBefore(LocalDate.of(2026, 10, 6));
    }

    @Test
    void 窗口是48小时() {
        assertThat(CouponExpiryReminderJob.WINDOW).isEqualTo(Duration.ofHours(48));
        assertThat(CouponExpiryReminderJob.MAX_USERS_PER_RUN).isEqualTo(20_000);
    }
}
