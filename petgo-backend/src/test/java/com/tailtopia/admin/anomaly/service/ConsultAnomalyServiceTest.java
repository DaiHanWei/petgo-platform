package com.tailtopia.admin.anomaly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import com.tailtopia.admin.anomaly.domain.AnomalyType;
import com.tailtopia.admin.anomaly.domain.ConsultAnomaly;
import com.tailtopia.admin.anomaly.repository.ConsultAnomalyRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.consult.event.ConsultAnomalyRaisedEvent;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** L0：异常工单落库去重 + 备注/标记已处理（Story 5.1）。NFR5：仅元数据，不触第三方。 */
class ConsultAnomalyServiceTest {

    private ConsultAnomalyRepository anomalies;
    private AdminAuditService auditService;
    private ConsultAnomalyService service;

    @BeforeEach
    void setUp() {
        anomalies = mock(ConsultAnomalyRepository.class);
        auditService = mock(AdminAuditService.class);
        service = new ConsultAnomalyService(anomalies, auditService);
    }

    private ConsultAnomalyRaisedEvent event(long sessionId) {
        return new ConsultAnomalyRaisedEvent(sessionId, 100L, 200L,
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-01T01:00:00Z"), "VET_BANNED");
    }

    @Test
    void recordsTicketForNewSession() {
        when(anomalies.existsBySessionId(7L)).thenReturn(false);
        service.recordTicket(event(7L));
        verify(anomalies).save(any(ConsultAnomaly.class));
    }

    @Test
    void skipsWhenSessionAlreadyHasTicket() {
        when(anomalies.existsBySessionId(7L)).thenReturn(true);
        service.recordTicket(event(7L));
        verify(anomalies, never()).save(any());
    }

    @Test
    void swallowsUniqueConstraintRaceAsAlreadyExists() {
        when(anomalies.existsBySessionId(7L)).thenReturn(false);
        when(anomalies.save(any(ConsultAnomaly.class)))
                .thenThrow(new DataIntegrityViolationException("dup session_id"));
        assertThatCode(() -> service.recordTicket(event(7L))).doesNotThrowAnyException();
    }

    @Test
    void addNotePersistsAndAudits() {
        ConsultAnomaly a = ConsultAnomaly.open(7L, 100L, 200L, null, null, "INTERRUPTED", AnomalyType.VET_BANNED);
        when(anomalies.findById(3L)).thenReturn(Optional.of(a));

        service.addNote(3L, "用户已电话安抚", 9L);

        assertThat(a.getInternalNote()).isEqualTo("用户已电话安抚");
        verify(anomalies).save(a);
        verify(auditService).record(eq(9L), eq(AuditActions.ANOMALY_NOTE_ADDED), eq("CONSULT_ANOMALY"),
                eq("3"), any());
    }

    @Test
    void addNoteRejectsBlank() {
        when(anomalies.findById(3L)).thenReturn(Optional.of(
                ConsultAnomaly.open(7L, 100L, 200L, null, null, "INTERRUPTED", AnomalyType.VET_BANNED)));
        assertThatThrownBy(() -> service.addNote(3L, "  ", 9L)).isInstanceOf(AppException.class);
        verify(auditService, never()).record(anyLong(), any(), any(), any(), any());
    }

    @Test
    void resolveArchivesWithImageKeyAndAudits() {
        ConsultAnomaly a = ConsultAnomaly.open(7L, 100L, 200L, null, null, "INTERRUPTED", AnomalyType.VET_BANNED);
        when(anomalies.findById(3L)).thenReturn(Optional.of(a));

        service.resolve(3L, "anomaly/img-key.jpg", 9L);

        assertThat(a.getStatus()).isEqualTo(AnomalyStatus.RESOLVED);
        assertThat(a.getResolvedBy()).isEqualTo(9L);
        assertThat(a.getResolutionImageKey()).isEqualTo("anomaly/img-key.jpg");
        verify(auditService).record(eq(9L), eq(AuditActions.ANOMALY_RESOLVED), eq("CONSULT_ANOMALY"),
                eq("3"), any());
    }
}
