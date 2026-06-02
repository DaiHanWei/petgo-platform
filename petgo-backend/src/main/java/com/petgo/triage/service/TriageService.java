package com.petgo.triage.service;

import com.petgo.shared.error.AppException;
import com.petgo.triage.domain.TriageStatus;
import com.petgo.triage.domain.TriageTask;
import com.petgo.triage.dto.TriageAcceptedResponse;
import com.petgo.triage.dto.TriageResultResponse;
import com.petgo.triage.dto.TriageSubmitRequest;
import com.petgo.triage.dto.TriageUpgradeContext;
import com.petgo.triage.event.TriageSubmittedEvent;
import com.petgo.triage.repository.TriageTaskRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分诊受理 + 取结果服务（Story 4.1）。{@code POST /triage} 受理（幂等 + 入队事件，202）；
 * {@code GET /triage/{id}} 短轮询取结果（仅本人可读）。真正的 Gemini 处理在 {@link TriageProcessor}。
 *
 * <p>护栏：{@code userId} 一律取自 JWT，不信任客户端；症状/图片为健康数据，本类不落日志。
 */
@Service
public class TriageService {

    private final TriageTaskRepository tasks;
    private final ApplicationEventPublisher events;

    public TriageService(TriageTaskRepository tasks, ApplicationEventPublisher events) {
        this.tasks = tasks;
        this.events = events;
    }

    /**
     * 受理分诊（AC1）。同 Idempotency-Key 命中既有任务直接回原 triageId，不重复入队；
     * 否则建 PENDING 任务并发 {@link TriageSubmittedEvent}（AFTER_COMMIT 异步处理）。
     */
    @Transactional
    public TriageAcceptedResponse submit(long userId, TriageSubmitRequest req, String idempotencyKey) {
        String key = emptyToNull(idempotencyKey);
        if (key != null) {
            Optional<TriageTask> existing = tasks.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                TriageTask t = existing.get();
                return TriageAcceptedResponse.of(t.getId(), t.getStatus());
            }
        }
        TriageTask task = tasks.save(TriageTask.submit(
                userId, req.petId(), req.symptomText(), req.imageObjectKeys(), key));
        // 提交后再异步处理：AFTER_COMMIT 保证任务已落库可见。
        events.publishEvent(new TriageSubmittedEvent(task.getId(), Instant.now()));
        return TriageAcceptedResponse.of(task.getId(), task.getStatus());
    }

    /**
     * 短轮询取结果（AC2）。仅 task 所属 {@code userId} 可读；越权 / 不存在均返 <b>同一</b> 403
     * （防枚举：他人 task 与不存在 task 文案不可区分，参 UX-DR18 ④）。
     */
    @Transactional(readOnly = true)
    public TriageResultResponse getResult(long userId, long triageId) {
        TriageTask task = tasks.findById(triageId).orElse(null);
        if (task == null || task.getUserId() != userId) {
            throw AppException.forbidden("无权访问该分诊任务");
        }
        return TriageResultResponse.from(task);
    }

    /**
     * 升级兽医的上下文（Story 5.4）。供 consult 模块经 service 接口拉取（禁直读 triage repository）。
     *
     * <p>仅本人、且任务已 DONE 可升级；越权/不存在统一 403 防枚举。返回评级/症状/私密图 key 快照。
     * <b>RED 红线由 consult 侧兜底拒绝</b>，本方法仍如实返回级别（不在 triage 侧判，职责单一）。
     */
    @Transactional(readOnly = true)
    public TriageUpgradeContext getResultForUpgrade(long userId, long triageTaskId) {
        TriageTask task = tasks.findById(triageTaskId).orElse(null);
        if (task == null || task.getUserId() != userId) {
            throw AppException.forbidden("无权访问该分诊任务");
        }
        if (task.getStatus() != TriageStatus.DONE || task.getDangerLevel() == null) {
            throw AppException.conflict("分诊尚未完成，暂不能升级兽医");
        }
        return new TriageUpgradeContext(
                task.getId(), task.getDangerLevel(), task.getSymptomText(), task.getImageObjectKeys());
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
