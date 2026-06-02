package com.petgo.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.consult.domain.ClosedReason;
import com.petgo.consult.domain.ConsultRating;
import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.domain.ConsultSource;
import com.petgo.consult.domain.RatingPromptState;
import com.petgo.consult.domain.SessionStatus;
import com.petgo.consult.event.ConsultClosedEvent;
import com.petgo.consult.repository.ConsultRatingRepository;
import com.petgo.consult.repository.ConsultSessionRepository;
import com.petgo.shared.error.AppException;
import com.petgo.shared.im.TencentImClient;
import com.petgo.vet.service.VetPresenceService;
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
}
