package com.tailtopia.profile.service;

import com.tailtopia.consult.event.ConsultClosedEvent;
import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.HealthSourceType;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 问诊结束 → 归档进成长档案（Bug 20260701-139 / P2 091「后端没有做归档」）。
 *
 * <p>会话 CLOSED 时 {@link ConsultClosedEvent} 触发：写一条 {@code ARCHIVED} 的 {@code VET_CONSULT}
 * 健康事件，成长日历据此在问诊当天显示 🏥 标（{@code hasHealthEvent}）、统计「问诊 X 次」+1。
 * 与 AI 分诊存档（{@code HealthEventService.recordDecision}）落同一张 {@code health_events} 表。
 *
 * <p>护栏（架构 §Enforcement）：仅 {@code @TransactionalEventListener}(AFTER_COMMIT)+{@code @Async} 订阅既有
 * 领域事件，跨模块不直调 consult repository；幂等按 {@code source_ref = consult:<sessionId>}（DB 唯一约束兜底）。
 * {@code petId} 会话不持有 → 按 {@code userId} 反查（V1 单宠物）；无档案（注销匿名化）则跳过。
 * 症状/诊断属健康数据：随事件携带、仅写库，绝不进日志。
 */
@Component
public class ConsultGrowthArchiveListener {

    private final HealthEventRepository healthEvents;
    private final PetProfileRepository petProfiles;

    public ConsultGrowthArchiveListener(HealthEventRepository healthEvents,
            PetProfileRepository petProfiles) {
        this.healthEvents = healthEvents;
        this.petProfiles = petProfiles;
    }

    @Async
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onConsultClosed(ConsultClosedEvent e) {
        String sourceRef = "consult:" + e.sessionId();
        if (healthEvents.existsBySourceRef(sourceRef)) {
            return; // 幂等：同一会话只归档一次。
        }
        petProfiles.findByOwnerId(e.userId()).ifPresent(pet -> {
            try {
                healthEvents.save(HealthEvent.archived(
                        pet.getId(), HealthSourceType.VET_CONSULT, sourceRef,
                        e.eventDate(), e.symptomSummary(), e.aiLevel(), e.adviceSummary(), null));
            } catch (DataIntegrityViolationException ignored) {
                // 并发同 sourceRef：唯一约束兜底，归一为幂等已归档。
            }
        });
    }
}
