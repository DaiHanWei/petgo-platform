package com.tailtopia.triage.service;

import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
import com.tailtopia.triage.repository.TriageTaskRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 分诊启动重扫（Story 4.1，AC3）。应用就绪（{@link ApplicationReadyEvent}）后异步扫
 * status ∈ {PENDING, PROCESSING} 的残留任务并续跑——崩溃/重启不丢任务、不留卡死的 PROCESSING。
 *
 * <p>仅用 DB 状态机重扫（<b>禁引入 MQ / 定时中间件</b>，V1 轻量姿态）。重试上限由
 * {@link TriageProcessor}（≤3）统一兜底。
 */
@Component
public class TriageTaskScanner {

    private static final Logger log = LoggerFactory.getLogger(TriageTaskScanner.class);

    private final TriageTaskRepository tasks;
    private final TriageProcessor processor;

    public TriageTaskScanner(TriageTaskRepository tasks, TriageProcessor processor) {
        this.tasks = tasks;
        this.processor = processor;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void rescanOnStartup() {
        List<TriageTask> residual = tasks.findByStatusIn(
                List.of(TriageStatus.PENDING, TriageStatus.PROCESSING));
        if (residual.isEmpty()) {
            return;
        }
        log.info("启动重扫分诊残留任务 count={}", residual.size());
        for (TriageTask t : residual) {
            try {
                processor.process(t.getId());
            } catch (RuntimeException e) {
                log.warn("重扫处理失败 triageId={} cause={}", t.getId(), e.getClass().getSimpleName());
            }
        }
    }
}
