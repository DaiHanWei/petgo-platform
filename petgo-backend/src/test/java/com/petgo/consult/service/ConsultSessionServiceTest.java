package com.petgo.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.domain.ConsultSource;
import com.petgo.consult.domain.SessionStatus;
import com.petgo.consult.repository.ConsultSessionRepository;
import com.petgo.consult.service.ConsultSessionService.CreateResult;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0 单元测试（无 DB/redis，mock repo+queue）：发起 WAITING 入队、同时仅 1 个、取消出队、超时不迁移状态、归属校验。
 */
@ExtendWith(MockitoExtension.class)
class ConsultSessionServiceTest {

    @Mock
    ConsultSessionRepository repo;
    @Mock
    ConsultQueueService queue;

    private ConsultSessionService service() {
        return new ConsultSessionService(repo, queue);
    }

    @Test
    void createWaitingPersistsAndEnqueuesWhenNoActive() {
        when(repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(repo.save(any(ConsultSession.class))).thenAnswer(inv -> {
            ConsultSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 11L); // 模拟 IDENTITY 落库分配主键
            return s;
        });

        CreateResult result = service().createWaiting(7L, ConsultSource.DIRECT);

        assertThat(result.alreadyActive()).isFalse();
        assertThat(result.session().getStatus()).isEqualTo(SessionStatus.WAITING);
        assertThat(result.session().getWaitingStartedAt()).isNotNull();
        verify(queue).enqueue(11L);
    }

    @Test
    void createWaitingReturnsExistingWhenAlreadyActive() {
        ConsultSession existing = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        when(repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.of(existing));

        CreateResult result = service().createWaiting(7L, ConsultSource.DIRECT);

        assertThat(result.alreadyActive()).isTrue();
        assertThat(result.session()).isSameAs(existing);
        verify(repo, never()).save(any());
        verify(queue, never()).enqueue(anyLong());
    }

    @Test
    void cancelTransitionsToCancelledAndDequeues() {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        when(repo.findById(11L)).thenReturn(Optional.of(s));
        when(repo.save(any(ConsultSession.class))).thenAnswer(inv -> inv.getArgument(0));

        service().cancel(7L, 11L);

        assertThat(s.getStatus()).isEqualTo(SessionStatus.CANCELLED);
        verify(queue).dequeue(11L);
    }

    @Test
    void cancelRejectsForeignOwnerAsNotFound() {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        when(repo.findById(11L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service().cancel(999L, 11L)).isInstanceOf(AppException.class);
        verify(queue, never()).dequeue(anyLong());
    }

    @Test
    void timedOutComputedFromWaitingStartButStaysWaiting() {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        // 默认 startWaiting 计时刚开始 → 未超时
        assertThat(s.isTimedOut(ConsultSessionService.WAITING_TIMEOUT_SECONDS)).isFalse();
        // continueWaiting 重置计时基准（仍 WAITING）
        when(repo.findById(11L)).thenReturn(Optional.of(s));
        when(repo.save(any(ConsultSession.class))).thenAnswer(inv -> inv.getArgument(0));
        Instant before = s.getWaitingStartedAt();
        service().continueWaiting(7L, 11L);
        assertThat(s.getStatus()).isEqualTo(SessionStatus.WAITING);
        assertThat(s.getWaitingStartedAt()).isAfterOrEqualTo(before);
    }
}
