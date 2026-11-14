package com.tailtopia.healthreminder.service;

import com.tailtopia.consult.event.ConsultClosedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 兽医问诊结束 → 记一次到访并重建该宠物（V1.5.0 Story 2.2 · AC1，架构 Z-8）。AFTER_COMMIT + {@code @Async}，写库在另一个
 * bean 的 REQUIRES_NEW 里。{@code petId} 为空（无订单的旧会话）→ 不写，不回落默认宠物（避免算错宠物）。
 * AI 分诊不发这个事件。
 */
@Component
public class VetConsultVisitListener {

    private static final Logger log = LoggerFactory.getLogger(VetConsultVisitListener.class);

    private final VetConsultVisitWriter writer;
    private final HealthReminderTriggers triggers;

    public VetConsultVisitListener(VetConsultVisitWriter writer, HealthReminderTriggers triggers) {
        this.writer = writer;
        this.triggers = triggers;
    }

    @Async
    @TransactionalEventListener
    public void onConsultClosed(ConsultClosedEvent e) {
        if (e.petId() == null) {
            return;
        }
        try {
            writer.record(e.sessionId(), e.petId(), e.eventDate());
        } catch (RuntimeException ex) {
            log.warn("health vet consult visit failed sessionId={} error={}", e.sessionId(),
                    ex.getClass().getSimpleName());
            return;
        }
        triggers.rebuild(e.petId(), RebuildTrigger.of("consult_closed"));
    }
}
