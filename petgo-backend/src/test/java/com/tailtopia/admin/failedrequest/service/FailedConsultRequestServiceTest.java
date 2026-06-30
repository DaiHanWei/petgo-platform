package com.tailtopia.admin.failedrequest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.failedrequest.domain.CancelReason;
import com.tailtopia.admin.failedrequest.domain.FailedConsultRequest;
import com.tailtopia.admin.failedrequest.repository.FailedConsultRequestRepository;
import com.tailtopia.consult.event.ConsultRequestFailedEvent;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：失败请求落库 + 跟进/归档/备注审计 + SYSTEM_FAILURE 强制跟进（Story 2.9）。 */
class FailedConsultRequestServiceTest {

    private FailedConsultRequestRepository repo;
    private AdminAuditService audit;
    private FailedConsultRequestService service;

    @BeforeEach
    void setUp() {
        repo = mock(FailedConsultRequestRepository.class);
        audit = mock(AdminAuditService.class);
        service = new FailedConsultRequestService(repo, audit);
        when(repo.save(any(FailedConsultRequest.class))).thenAnswer(i -> i.getArgument(0));
    }

    private FailedConsultRequest record(CancelReason reason, boolean followed) {
        FailedConsultRequest r = FailedConsultRequest.of("tok", 7L, 11L, Instant.now(), Instant.now(), reason, 3);
        ReflectionTestUtils.setField(r, "id", 50L);
        if (followed) {
            r.markFollowedUp();
        }
        return r;
    }

    @Test
    void listenerPersistsWithTokenAndReason() {
        service.onConsultRequestFailed(new ConsultRequestFailedEvent(
                "SYSTEM_FAILURE", 7L, 11L, Instant.now(), 4));

        ArgumentCaptor<FailedConsultRequest> cap = ArgumentCaptor.forClass(FailedConsultRequest.class);
        verify(repo).save(cap.capture());
        FailedConsultRequest saved = cap.getValue();
        assertThat(saved.getCancelReason()).isEqualTo(CancelReason.SYSTEM_FAILURE);
        assertThat(saved.getRequestToken()).isNotBlank();
        assertThat(saved.getOnlineVetCount()).isEqualTo(4);
        assertThat(saved.isFollowedUp()).isFalse();
    }

    @Test
    void unknownReasonDefaultsToUserCancel() {
        service.onConsultRequestFailed(new ConsultRequestFailedEvent("???", 7L, 11L, Instant.now(), 0));
        ArgumentCaptor<FailedConsultRequest> cap = ArgumentCaptor.forClass(FailedConsultRequest.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getCancelReason()).isEqualTo(CancelReason.USER_CANCEL);
    }

    @Test
    void followUpAndAudit() {
        when(repo.findById(50L)).thenReturn(Optional.of(record(CancelReason.SYSTEM_FAILURE, false)));
        service.followUp(50L, 3L);
        verify(audit).record(eq(3L), eq(AuditActions.FAILED_REQUEST_FOLLOWED_UP), any(), eq("50"), any());
    }

    @Test
    void archiveSystemFailureRequiresFollowUp() {
        when(repo.findById(50L)).thenReturn(Optional.of(record(CancelReason.SYSTEM_FAILURE, false)));
        assertThatThrownBy(() -> service.archive(50L, 3L)).isInstanceOf(AppException.class);
    }

    @Test
    void archiveSystemFailureAfterFollowUpSucceedsAndAudits() {
        when(repo.findById(50L)).thenReturn(Optional.of(record(CancelReason.SYSTEM_FAILURE, true)));
        service.archive(50L, 3L);
        verify(audit).record(eq(3L), eq(AuditActions.FAILED_REQUEST_ARCHIVED), any(), eq("50"), any());
    }

    @Test
    void archiveNonSystemFailureWithoutFollowUpOk() {
        when(repo.findById(50L)).thenReturn(Optional.of(record(CancelReason.USER_CANCEL, false)));
        service.archive(50L, 3L);
        verify(audit).record(eq(3L), eq(AuditActions.FAILED_REQUEST_ARCHIVED), any(), eq("50"), any());
    }
}
