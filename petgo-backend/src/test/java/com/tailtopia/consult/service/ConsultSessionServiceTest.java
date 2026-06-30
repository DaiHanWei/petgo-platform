package com.tailtopia.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.consult.service.ConsultSessionService.CreateResult;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.dto.TriageUpgradeContext;
import com.tailtopia.triage.service.TriageService;
import java.time.Instant;
import java.util.List;
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
    @Mock
    TriageService triageService;
    @Mock
    org.springframework.context.ApplicationEventPublisher events;
    @Mock
    com.tailtopia.vet.service.VetPresenceService presence;

    private ConsultSessionService service() {
        return new ConsultSessionService(repo, queue, triageService, events, presence);
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
        when(presence.onlineVetIds()).thenReturn(java.util.List.of(1L, 2L));

        service().cancel(7L, 11L);

        assertThat(s.getStatus()).isEqualTo(SessionStatus.CANCELLED);
        verify(queue).dequeue(11L);
        // Story 2.9：取消发失败请求事件（USER_CANCEL，含失败时刻在线兽医数）。
        verify(events).publishEvent(any(com.tailtopia.consult.event.ConsultRequestFailedEvent.class));
    }

    @Test
    void cancelRejectsForeignOwnerAsNotFound() {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        when(repo.findById(11L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service().cancel(999L, 11L)).isInstanceOf(AppException.class);
        verify(queue, never()).dequeue(anyLong());
    }

    @Test
    void upgradeBindsAiContextSnapshotForYellow() {
        when(repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(triageService.getResultForUpgrade(7L, 99L)).thenReturn(
                new TriageUpgradeContext(99L, DangerLevel.YELLOW, "呕吐两次", List.of("k1", "k2")));
        when(repo.save(any(ConsultSession.class))).thenAnswer(inv -> {
            ConsultSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 21L);
            return s;
        });

        CreateResult result = service().createWaitingFromUpgrade(7L, 99L);

        assertThat(result.alreadyActive()).isFalse();
        assertThat(result.session().getSource()).isEqualTo(ConsultSource.AI_UPGRADE);
        assertThat(result.session().getAiDangerLevel()).isEqualTo("YELLOW");
        assertThat(result.session().getAiSymptomText()).isEqualTo("呕吐两次");
        assertThat(result.session().getAiImageRefs()).containsExactly("k1", "k2");
        assertThat(result.session().hasAiContext()).isTrue();
        verify(queue).enqueue(21L);
    }

    @Test
    void upgradeRejectsRedDangerLevel() {
        when(repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(triageService.getResultForUpgrade(7L, 99L)).thenReturn(
                new TriageUpgradeContext(99L, DangerLevel.RED, "抽搐", List.of()));

        assertThatThrownBy(() -> service().createWaitingFromUpgrade(7L, 99L))
                .isInstanceOf(AppException.class);
        verify(repo, never()).save(any());
        verify(queue, never()).enqueue(anyLong());
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
