package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.repository.PetDiaryNotifyLogDao;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * L0：追更日提醒「插日志 + 写通知」同一事务（V1.4.2 social-topic · Story 1.2 · 复审 1）。
 *
 * <p>Mockito 层面能证的范围：① 日志冲突 → 不写通知；② 先插日志再写通知；③ 写通知异常原样抛出（事务回滚的前提，
 * 默认 rollback-on-RuntimeException）；④ 事务边界注解：本方法 REQUIRES_NEW，日志插入与写通知都是 REQUIRED（加入同一事务，
 * 写通知失败时日志行随之回滚、不残留）。真实回滚在 L1 由 {@code PetDiaryNotifyIntegrationTest} 验。
 */
class PetDiaryNotifyWriterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    private final PetDiaryNotifyLogDao logDao = mock(PetDiaryNotifyLogDao.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PetDiaryNotifyWriter writer = new PetDiaryNotifyWriter(logDao, notifications);

    @Test
    void conflictWritesNothing() {
        when(logDao.tryInsert(10L, 70L, DAY)).thenReturn(false);
        assertThat(writer.notifyIfFirstToday(10L, 555L, 70L, "Mochi", DAY)).isFalse();
        verify(notifications, never()).sendPetDiaryPosted(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void insertsLogThenWritesNotification() {
        when(logDao.tryInsert(10L, 70L, DAY)).thenReturn(true);
        assertThat(writer.notifyIfFirstToday(10L, 555L, 70L, "Mochi", DAY)).isTrue();
        InOrder order = inOrder(logDao, notifications);
        order.verify(logDao).tryInsert(10L, 70L, DAY);
        order.verify(notifications).sendPetDiaryPosted(10L, 555L, 70L, "Mochi");
    }

    @Test
    void notificationFailurePropagatesSoTheSharedTransactionRollsBack() {
        when(logDao.tryInsert(10L, 70L, DAY)).thenReturn(true);
        when(notifications.sendPetDiaryPosted(10L, 555L, 70L, "Mochi")).thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(() -> writer.notifyIfFirstToday(10L, 555L, 70L, "Mochi", DAY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transactionBoundariesShareOneNewTransaction() throws Exception {
        Transactional outer = PetDiaryNotifyWriter.class.getMethod("notifyIfFirstToday", long.class, long.class,
                long.class, String.class, LocalDate.class).getAnnotation(Transactional.class);
        assertThat(outer.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(outer.noRollbackFor()).isEmpty();
        Transactional log = PetDiaryNotifyLogDao.class.getMethod("tryInsert", long.class, long.class, LocalDate.class)
                .getAnnotation(Transactional.class);
        assertThat(log.propagation()).isEqualTo(Propagation.REQUIRED);
        Transactional send = NotificationService.class.getMethod("sendPetDiaryPosted", long.class, long.class,
                long.class, String.class).getAnnotation(Transactional.class);
        assertThat(send.propagation()).isEqualTo(Propagation.REQUIRED);
    }
}
