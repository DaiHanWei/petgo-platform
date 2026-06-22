package com.tailtopia.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.triage.TriageTestSupport;
import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
import com.tailtopia.triage.dto.TriageAcceptedResponse;
import com.tailtopia.triage.dto.TriageResultResponse;
import com.tailtopia.triage.dto.TriageSubmitRequest;
import com.tailtopia.triage.event.TriageSubmittedEvent;
import com.tailtopia.triage.repository.TriageTaskRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** L0：受理幂等 + 入队事件（AC1）+ 取结果鉴权防枚举（AC2）。 */
class TriageServiceTest {

    private TriageTaskRepository tasks;
    private ApplicationEventPublisher events;
    private TriageService service;

    @BeforeEach
    void setUp() {
        tasks = mock(TriageTaskRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new TriageService(tasks, events);
    }

    @Test
    void submitPersistsPendingAndPublishesEvent() {
        when(tasks.save(any())).thenAnswer(inv -> {
            TriageTask t = inv.getArgument(0);
            TriageTestSupport.set(t, "id", 42L);
            return t;
        });

        TriageAcceptedResponse resp = service.submit(
                7L, new TriageSubmitRequest("咳嗽", List.of("k1"), null), null, "en");

        assertThat(resp.triageId()).isEqualTo(42L);
        assertThat(resp.status()).isEqualTo(TriageStatus.PENDING);
        ArgumentCaptor<TriageSubmittedEvent> ev = ArgumentCaptor.forClass(TriageSubmittedEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().triageId()).isEqualTo(42L);
    }

    @Test
    void submitWithSameIdempotencyKeyReturnsExistingWithoutEnqueue() {
        TriageTask existing = TriageTestSupport.task(99L, 7L, TriageStatus.PROCESSING, "x", null);
        when(tasks.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

        TriageAcceptedResponse resp = service.submit(
                7L, new TriageSubmitRequest("咳嗽", null, null), "idem-1", "en");

        assertThat(resp.triageId()).isEqualTo(99L);
        verify(tasks, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void getResultReturnsDoneStructureForOwner() {
        TriageTask done = TriageTestSupport.task(5L, 7L, TriageStatus.DONE, "x", null);
        TriageTestSupport.set(done, "dangerLevel", com.tailtopia.triage.domain.DangerLevel.YELLOW);
        TriageTestSupport.set(done, "parsedResult",
                java.util.Map.of("advice", "尽快就医", "disclaimer", "仅供参考"));
        when(tasks.findById(5L)).thenReturn(Optional.of(done));

        TriageResultResponse resp = service.getResult(7L, 5L);

        assertThat(resp.status()).isEqualTo(TriageStatus.DONE);
        assertThat(resp.dangerLevel()).isEqualTo(com.tailtopia.triage.domain.DangerLevel.YELLOW);
        assertThat(resp.advice()).isEqualTo("尽快就医");
    }

    @Test
    void getResultProcessingReturnsStatusOnly() {
        TriageTask proc = TriageTestSupport.task(5L, 7L, TriageStatus.PROCESSING, "x", null);
        when(tasks.findById(5L)).thenReturn(Optional.of(proc));

        TriageResultResponse resp = service.getResult(7L, 5L);

        assertThat(resp.status()).isEqualTo(TriageStatus.PROCESSING);
        assertThat(resp.dangerLevel()).isNull();
        assertThat(resp.advice()).isNull();
    }

    @Test
    void getResultForNonOwnerAndMissingAreIndistinguishable403() {
        // 越权：他人 task
        TriageTask others = TriageTestSupport.task(5L, 999L, TriageStatus.DONE, "x", null);
        when(tasks.findById(5L)).thenReturn(Optional.of(others));
        // 不存在
        when(tasks.findById(6L)).thenReturn(Optional.empty());

        AppException forbidden = catchForbidden(() -> service.getResult(7L, 5L));
        AppException notFound = catchForbidden(() -> service.getResult(7L, 6L));

        // 防枚举：两者状态码与文案完全一致
        assertThat(forbidden.getStatus()).isEqualTo(notFound.getStatus());
        assertThat(forbidden.getMessage()).isEqualTo(notFound.getMessage());
    }

    private static AppException catchForbidden(Runnable r) {
        try {
            r.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("expected AppException");
    }

    @Test
    void getResultThrowsForbiddenNotNotFound() {
        when(tasks.findById(6L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getResult(7L, 6L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(403));
    }
}
