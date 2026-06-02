package com.petgo.triage.service;

import com.petgo.triage.event.TriageSubmittedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 分诊提交事件监听（Story 4.1）。{@code @Async @TransactionalEventListener(AFTER_COMMIT)}：
 * 在受理事务提交后、异步线程中触发处理，保证任务行已落库可见再调 Gemini。
 *
 * <p>独立 bean（非 {@link TriageProcessor} 内自调）以确保 {@code @Async} 代理生效；处理委托给
 * {@link TriageProcessor#process}（跨 bean 调用，{@code @Transactional} 生效）。
 */
@Component
public class TriageEventListener {

    private final TriageProcessor processor;

    public TriageEventListener(TriageProcessor processor) {
        this.processor = processor;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(TriageSubmittedEvent event) {
        processor.process(event.triageId());
    }
}
