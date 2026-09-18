package com.tailtopia.admin.anomaly.service;

import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import com.tailtopia.admin.anomaly.domain.AnomalyType;
import com.tailtopia.admin.anomaly.domain.ConsultAnomaly;
import com.tailtopia.admin.anomaly.repository.ConsultAnomalyRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.consult.event.ConsultAnomalyRaisedEvent;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 问诊异常工单（Story 5.1，AB-4A）。订阅 Story 2.5 封禁流的 {@link ConsultAnomalyRaisedEvent} 落工单（去重幂等），
 * 提供队列查询 + 内部备注 + 标记已处理（归档）。**仅元数据，绝不读第三方 IM/AI（NFR5）**；写操作写审计哈希链。
 */
@Service
public class ConsultAnomalyService {

    private static final Logger log = LoggerFactory.getLogger(ConsultAnomalyService.class);
    /** VET_BANNED 异常一律由封禁中断产生，会话状态快照恒为 INTERRUPTED。 */
    private static final String SESSION_STATUS_INTERRUPTED = "INTERRUPTED";

    private final ConsultAnomalyRepository anomalies;
    private final AdminAuditService auditService;

    public ConsultAnomalyService(ConsultAnomalyRepository anomalies, AdminAuditService auditService) {
        this.anomalies = anomalies;
        this.auditService = auditService;
    }

    /** 异步订阅封禁异常事件 → 落工单（独立线程，与封禁事务最终一致；48h SLA 非强一致）。 */
    @Async
    @EventListener
    public void onAnomalyRaised(ConsultAnomalyRaisedEvent event) {
        recordTicket(event);
    }

    /**
     * 落一条工单（去重幂等）：已存在 session_id 跳过；并发撞唯一索引 → 捕获当作已存在吞掉。
     * 公开方法以便集成测试同步驱动（@Async 监听仅薄委托）。
     */
    @Transactional
    public void recordTicket(ConsultAnomalyRaisedEvent event) {
        if (anomalies.existsBySessionId(event.sessionId())) {
            return; // 首道去重
        }
        try {
            anomalies.save(ConsultAnomaly.open(event.sessionId(), event.userId(), event.vetId(),
                    event.startedAt(), event.endedAt(), SESSION_STATUS_INTERRUPTED,
                    AnomalyType.valueOf(event.anomalyType())));
        } catch (DataIntegrityViolationException dup) {
            // 并发兜底：唯一索引冲突＝已存在，幂等吞掉（不记 PII/健康数据）。
            log.info("异常工单已存在，跳过 sessionId={}", event.sessionId());
        }
    }

    @Transactional(readOnly = true)
    public List<ConsultAnomaly> list(AnomalyStatus status) {
        return status == null
                ? anomalies.findAllByOrderByCreatedAtDesc()
                : anomalies.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public Optional<ConsultAnomaly> find(long anomalyId) {
        return anomalies.findById(anomalyId);
    }

    /** 工作台分页（V1.3.0 Story 2.6，只读）：按状态、时间倒序。 */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ConsultAnomaly> page(AnomalyStatus status,
            org.springframework.data.domain.Pageable pageable) {
        return anomalies.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    /** 两态计数（只读）。 */
    @Transactional(readOnly = true)
    public long count(AnomalyStatus status) {
        return anomalies.countByStatus(status);
    }

    /** 处置后「下一条」= 当前最新一条 OPEN 的 id；无则空串（只读）。 */
    @Transactional(readOnly = true)
    public String nextOpenId() {
        return anomalies.findFirstByStatusOrderByCreatedAtDesc(AnomalyStatus.OPEN)
                .map(a -> String.valueOf(a.getId())).orElse("");
    }

    /**
     * 追加一条内部备注到时间线（V1.3.0 Story 2.6 AC2）。{@code internal_note} 仍是那一列 TEXT，
     * 只是改为按行追加「{@code epochMillis|操作人|内容}」（见 {@link com.tailtopia.admin.anomaly.dto.AnomalyNoteLine}），
     * 不建表、不改 schema；{@link #addNote} 的覆盖语义与审计原样保留。备注用户不可见；内容不进日志。
     */
    @Transactional
    public void appendNote(long anomalyId, String note, long actorAccountId, String actorName) {
        if (note == null || note.isBlank()) {
            throw AppException.validation("备注内容不能为空").code("admin.err.anomaly.noteRequired");
        }
        if (note.trim().length() > NOTE_MAX_LENGTH) {
            throw AppException.validation("备注不能超过 500 字").code("admin.err.anomaly.noteTooLong");
        }
        String line = com.tailtopia.admin.anomaly.dto.AnomalyNoteLine.encode(Instant.now(),
                actorName == null || actorName.isBlank() ? "#" + actorAccountId : actorName, note.trim());
        // 数据库端追加（复审 #1）：并发加备注不丢行；0 行 = 工单不存在。
        if (anomalies.appendNoteLine(anomalyId, line) == 0) {
            throw AppException.notFound("异常工单不存在").code("admin.err.anomaly.notFound");
        }
        auditService.record(actorAccountId, AuditActions.ANOMALY_NOTE_ADDED, "CONSULT_ANOMALY",
                String.valueOf(anomalyId), "异常工单加内部备注");
    }

    /** 备注单条上限（与模板 maxlength 一致）。 */
    static final int NOTE_MAX_LENGTH = 500;

    /**
     * 加内部备注（用户不可见）+ 审计。<b>覆盖式</b>（Story 5.1 旧口径）。
     *
     * @deprecated V1.3.0 Story 2.6 起后台走 {@link #appendNote} 时间线追加；本方法会抹掉整条时间线，仅保留给旧调用方 / 旧测试。
     */
    @Deprecated
    @Transactional
    public void addNote(long anomalyId, String note, long actorAccountId) {
        if (note == null || note.isBlank()) {
            throw AppException.validation("备注内容不能为空").code("admin.err.anomaly.noteRequired");
        }
        ConsultAnomaly a = require(anomalyId);
        a.setInternalNote(note.trim());
        anomalies.save(a);
        auditService.record(actorAccountId, AuditActions.ANOMALY_NOTE_ADDED, "CONSULT_ANOMALY",
                String.valueOf(anomalyId), "异常工单加内部备注");
    }

    /** 标记已处理（归档）+ 选填处理图对象 key + 审计（summary 不含签名 URL/敏感内容）。 */
    @Transactional
    public void resolve(long anomalyId, String resolutionImageKey, long actorAccountId) {
        ConsultAnomaly a = require(anomalyId);
        if (a.getStatus() == AnomalyStatus.RESOLVED) {
            // 过期页面 / 双击重放：不覆盖处置人与时间、不重复审计（V1.3.0 Story 2.6 复审 #7）。
            throw AppException.conflict("该工单已处理").code("admin.err.anomaly.alreadyResolved");
        }
        a.resolve(actorAccountId, Instant.now(), resolutionImageKey);
        anomalies.save(a);
        auditService.record(actorAccountId, AuditActions.ANOMALY_RESOLVED, "CONSULT_ANOMALY",
                String.valueOf(anomalyId), "异常工单标记已处理并归档");
    }

    private ConsultAnomaly require(long anomalyId) {
        return anomalies.findById(anomalyId).orElseThrow(() -> AppException.notFound("异常工单不存在").code("admin.err.anomaly.notFound"));
    }
}
