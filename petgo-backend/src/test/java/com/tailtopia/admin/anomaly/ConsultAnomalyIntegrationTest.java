package com.tailtopia.admin.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import com.tailtopia.admin.anomaly.domain.ConsultAnomaly;
import com.tailtopia.admin.anomaly.repository.ConsultAnomalyRepository;
import com.tailtopia.admin.anomaly.service.ConsultAnomalyService;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.consult.event.ConsultAnomalyRaisedEvent;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * L1：异常工单（Story 5.1，需 Docker postgres+redis）。验 V42 迁移 validate（上下文启动即证）、
 * 事件落工单 + session_id 去重幂等、备注/标记已处理 + 审计哈希链。
 */
class ConsultAnomalyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultAnomalyService service;
    @Autowired
    private ConsultAnomalyRepository anomalies;
    @Autowired
    private AdminAuditService auditService;

    private ConsultAnomalyRaisedEvent event(long sessionId) {
        return new ConsultAnomalyRaisedEvent(sessionId, 7777L, 8888L,
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-01T01:00:00Z"), "VET_BANNED");
    }

    @Test
    void eventLandsTicketAndDedupesBySession() {
        long sessionId = 9_500_000L + SEQ.incrementAndGet();

        service.recordTicket(event(sessionId));
        service.recordTicket(event(sessionId)); // 重复事件不产重复工单

        assertThat(anomalies.findBySessionId(sessionId)).isPresent();
        long count = anomalies.findAllByOrderByCreatedAtDesc().stream()
                .filter(a -> a.getSessionId() == sessionId).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void noteThenResolveWithAudit() {
        long sessionId = 9_600_000L + SEQ.incrementAndGet();
        long actor = 510000L + SEQ.incrementAndGet();
        service.recordTicket(event(sessionId));
        long anomalyId = anomalies.findBySessionId(sessionId).orElseThrow().getId();

        service.addNote(anomalyId, "已电话联系用户", actor);
        service.resolve(anomalyId, "anomaly/" + sessionId + ".jpg", actor);

        ConsultAnomaly a = anomalies.findById(anomalyId).orElseThrow();
        assertThat(a.getInternalNote()).isEqualTo("已电话联系用户");
        assertThat(a.getStatus()).isEqualTo(AnomalyStatus.RESOLVED);
        assertThat(a.getResolutionImageKey()).isEqualTo("anomaly/" + sessionId + ".jpg");
        assertThat(a.getResolvedBy()).isEqualTo(actor);
        assertThat(auditService.search(null, null, actor, AuditActions.ANOMALY_NOTE_ADDED,
                PageRequest.of(0, 5)).getContent()).isNotEmpty();
        assertThat(auditService.search(null, null, actor, AuditActions.ANOMALY_RESOLVED,
                PageRequest.of(0, 5)).getContent()).isNotEmpty();
    }
}
