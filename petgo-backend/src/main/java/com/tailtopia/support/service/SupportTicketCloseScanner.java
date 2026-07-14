package com.tailtopia.support.service;

import com.tailtopia.support.domain.FeedbackTicket;
import com.tailtopia.support.domain.TicketStatus;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工单已解决 7 天自动关闭（Story 4.7，架构 §5.3 定时任务⑦）。DB 状态机驱动（{@code @Scheduled}，
 * <b>禁 MQ / 延迟队列</b>，enforcement 护栏）。仅 {@code RESOLVED} 且 {@code csat_deadline < now}（用户未评）
 * → {@code CLOSED}（无 CSAT，静默）。幂等：状态守卫（重扫不重复，已 CLOSED 不在扫描集）。
 */
@Component
public class SupportTicketCloseScanner {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketCloseScanner.class);

    private final FeedbackTicketRepository tickets;

    public SupportTicketCloseScanner(FeedbackTicketRepository tickets) {
        this.tickets = tickets;
    }

    @Scheduled(fixedDelayString = "${petgo.support.close-scan-ms:300000}")
    @Transactional
    public void closeExpiredResolved() {
        List<FeedbackTicket> expired =
                tickets.findByStatusAndCsatDeadlineBefore(TicketStatus.RESOLVED, Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        for (FeedbackTicket t : expired) {
            t.autoClose();
        }
        log.info("工单 CSAT 超期自动关闭 count={}", expired.size());
    }
}
