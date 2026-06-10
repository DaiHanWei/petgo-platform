package com.tailtopia.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.domain.ClosedReason;
import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.RatingPromptState;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.event.ConsultClosedEvent;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.vet.service.VetPresenceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0 单元测试：结束→PENDING_CLOSE+回在线、评分→CLOSED(RATED)+存档事件、30min 超时→CLOSED(UNRATED)+补弹、评分校验/归属。
 */
@ExtendWith(MockitoExtension.class)
class ConsultCloseServiceTest {

    @Mock
    ConsultSessionRepository sessions;
    @Mock
    ConsultRatingRepository ratings;
    @Mock
    VetPresenceService presence;
    @Mock
    TencentImClient imClient;
    @Mock
    ApplicationEventPublisher events;

    private ConsultCloseService service() {
        return new ConsultCloseService(sessions, ratings, presence, imClient, events);
    }

    private ConsultSession inProgress(long id, long vetId) {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        ReflectionTestUtils.setField(s, "id", id);
        s.markInProgress(vetId);
        s.attachImConversation("conv-1");
        return s;
    }

    @Test
    void endByVetMovesToPendingCloseAndFreesVet() {
        ConsultSession s = inProgress(11L, 3L);
        when(sessions.findById(11L)).thenReturn(Optional.of(s));

        service().endByVet(3L, 11L);

        assertThat(s.getStatus()).isEqualTo(SessionStatus.PENDING_CLOSE);
        assertThat(s.getPendingCloseStartedAt()).isNotNull();
        verify(presence).goAvailable(3L);
        verify(imClient).sendSystemMessage(org.mockito.ArgumentMatchers.eq("conv-1"),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void endByVetRejectsForeignVet() {
        ConsultSession s = inProgress(11L, 3L);
        when(sessions.findById(11L)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service().endByVet(99L, 11L)).isInstanceOf(AppException.class);
    }

    @Test
    void submitRatingClosesRatedAndPublishesArchiveEvent() {
        ConsultSession s = inProgress(11L, 3L);
        s.endByVet(); // PENDING_CLOSE
        when(sessions.findById(11L)).thenReturn(Optional.of(s));
        when(ratings.existsBySessionId(11L)).thenReturn(false);

        service().submitRating(7L, 11L, 5, "很专业");

        assertThat(s.getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(s.getClosedReason()).isEqualTo(ClosedReason.RATED);
        verify(ratings).save(any(ConsultRating.class));
        verify(events).publishEvent(any(ConsultClosedEvent.class));
    }

    @Test
    void submitRatingRejectsDuplicate() {
        ConsultSession s = inProgress(11L, 3L);
        s.endByVet();
        when(sessions.findById(11L)).thenReturn(Optional.of(s));
        when(ratings.existsBySessionId(11L)).thenReturn(true);

        assertThatThrownBy(() -> service().submitRating(7L, 11L, 5, null)).isInstanceOf(AppException.class);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void closeExpiredGatesClosesUnratedAndMarksPromptPending() {
        ConsultSession s = inProgress(11L, 3L);
        s.endByVet();
        when(sessions.findByStatusAndPendingCloseStartedAtBefore(
                org.mockito.ArgumentMatchers.eq(SessionStatus.PENDING_CLOSE), any(Instant.class)))
                .thenReturn(List.of(s));

        int closed = service().closeExpiredGates();

        assertThat(closed).isEqualTo(1);
        assertThat(s.getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(s.getClosedReason()).isEqualTo(ClosedReason.UNRATED);
        assertThat(s.getRatingPromptState()).isEqualTo(RatingPromptState.PENDING);
        verify(events).publishEvent(any(ConsultClosedEvent.class));
    }

    // ===== AC5（F12 · R2）：补评分推迟——有进行中会话则不补弹 =====

    @Test
    void pendingRatingDeferredWhenActiveSession() {
        ConsultSession active = inProgress(20L, 3L); // 用户有进行中会话
        when(sessions.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(7L, SessionStatus.ACTIVE))
                .thenReturn(Optional.of(active));

        assertThat(service().pendingRating(7L)).isEmpty(); // 推迟补弹
        // 有活跃会话时根本不查待补弹会话（推迟逻辑短路在前）。
        verify(sessions, never()).findFirstByUserIdAndStatusAndRatingPromptState(
                org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void pendingRatingReturnedWhenNoActiveSession() {
        ConsultSession closed = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        ReflectionTestUtils.setField(closed, "id", 21L);
        when(sessions.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(7L, SessionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(sessions.findFirstByUserIdAndStatusAndRatingPromptState(
                7L, SessionStatus.CLOSED, RatingPromptState.PENDING))
                .thenReturn(Optional.of(closed));

        assertThat(service().pendingRating(7L)).contains(closed); // 无活跃会话 → 放行补弹
    }
}
