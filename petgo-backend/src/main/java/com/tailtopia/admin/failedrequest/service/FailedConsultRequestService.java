package com.tailtopia.admin.failedrequest.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.failedrequest.domain.CancelReason;
import com.tailtopia.admin.failedrequest.domain.FailedConsultRequest;
import com.tailtopia.admin.failedrequest.repository.FailedConsultRequestRepository;
import com.tailtopia.consult.event.ConsultRequestFailedEvent;
import com.tailtopia.shared.error.AppException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 失败问诊请求落库与跟进（Story 2.9，AB-2G）。监听 consult 的 {@link ConsultRequestFailedEvent} 落自有表
 * （admin 拥有 failed_consult_requests，跨模块经事件、禁跨 repo）。跟进/归档/备注写审计。
 */
@Service
public class FailedConsultRequestService {

    private static final char[] BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private final FailedConsultRequestRepository repo;
    private final AdminAuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public FailedConsultRequestService(FailedConsultRequestRepository repo,
            AdminAuditService auditService) {
        this.repo = repo;
        this.auditService = auditService;
    }

    /** 监听失败事件 → 落库（生成不可枚举 token，followed_up=false）。 */
    @EventListener
    @Transactional
    public void onConsultRequestFailed(ConsultRequestFailedEvent e) {
        repo.save(FailedConsultRequest.of(generateToken(), e.userId(), e.sessionId(),
                e.submittedAt(), Instant.now(), CancelReason.fromOrDefault(e.reason()), e.onlineVetCount()));
    }

    @Transactional(readOnly = true)
    public List<FailedConsultRequest> active() {
        return repo.findByArchivedAtIsNullOrderByCancelledAtDesc();
    }

    @Transactional(readOnly = true)
    public List<FailedConsultRequest> archived() {
        return repo.findByArchivedAtIsNotNullOrderByArchivedAtDesc();
    }

    @Transactional
    public void followUp(long id, long actorAccountId) {
        FailedConsultRequest r = require(id);
        r.markFollowedUp();
        repo.save(r);
        auditService.record(actorAccountId, AuditActions.FAILED_REQUEST_FOLLOWED_UP,
                "FAILED_CONSULT_REQUEST", String.valueOf(id), "标记失败请求已跟进");
    }

    @Transactional
    public void archive(long id, long actorAccountId) {
        FailedConsultRequest r = require(id);
        r.archive(); // SYSTEM_FAILURE 未跟进会抛
        repo.save(r);
        auditService.record(actorAccountId, AuditActions.FAILED_REQUEST_ARCHIVED,
                "FAILED_CONSULT_REQUEST", String.valueOf(id), "归档失败请求");
    }

    @Transactional
    public void note(long id, String note, long actorAccountId) {
        FailedConsultRequest r = require(id);
        r.setNote(note);
        repo.save(r);
        auditService.record(actorAccountId, AuditActions.FAILED_REQUEST_NOTED,
                "FAILED_CONSULT_REQUEST", String.valueOf(id), "更新失败请求备注");
    }

    private FailedConsultRequest require(long id) {
        return repo.findById(id).orElseThrow(() -> AppException.notFound("记录不存在"));
    }

    private String generateToken() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }
}
