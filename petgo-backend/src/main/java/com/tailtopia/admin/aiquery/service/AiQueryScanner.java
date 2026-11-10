package com.tailtopia.admin.aiquery.service;

import com.tailtopia.admin.aiquery.agent.AgentJob;
import com.tailtopia.admin.aiquery.agent.AiQueryAgentClient;
import com.tailtopia.admin.aiquery.agent.AiQueryAgentClient.PollOutcome;
import com.tailtopia.admin.aiquery.agent.AiQueryAgentClient.SubmitOutcome;
import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryConversation;
import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import com.tailtopia.admin.aiquery.domain.AiQueryStatus;
import com.tailtopia.admin.aiquery.repository.AiQueryConversationRepository;
import com.tailtopia.admin.aiquery.repository.AiQueryMessageRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 单个 {@code @Scheduled} 扫描器（spec §4.1，fixedDelay 3 秒）：
 * <ol>
 * <li>超过 15 分钟未结束 → {@code TIMEOUT}；</li>
 * <li>把 {@code PENDING} 提交给 agent（按 jobToken 幂等，ops 重启后重复提交安全），202 → {@code RUNNING}；
 *     429 / 不可达 → 保持 {@code PENDING} 下轮重试；</li>
 * <li>轮询 {@code RUNNING}：完成 / 失败落库；agent 返回 404（不认识该任务）→ {@code FAILED}。</li>
 * </ol>
 * 不另开 {@code @Async}；{@code AI_QUERY_MODE=off} 时什么都不做。HTTP 调用都在事务外，状态写入走
 * {@link AiQueryTransitions}（带旧状态条件）。日志只出现消息 token 与状态，不出现问题和答案。
 */
@Component
public class AiQueryScanner {

    private static final Logger log = LoggerFactory.getLogger(AiQueryScanner.class);
    /** 每轮每类最多处理的任务数（agent 并发 2、排队 20，留余量）。 */
    static final int BATCH = 25;
    public static final String ERR_AGENT_LOST = "AGENT_LOST";
    public static final String ERR_AGENT_REJECTED = "AGENT_REJECTED";
    public static final String ERR_AUTH_FAILED = "AGENT_AUTH_FAILED";
    /** agent 契约里的失败码（ai-query-agent Story 1.1）；其他值一律归为 AGENT_ERROR，也保证不超列宽。 */
    static final java.util.Set<String> AGENT_ERROR_CODES = java.util.Set.of(
            "AGENT_RATE_LIMITED", ERR_AUTH_FAILED, "AGENT_TIMEOUT", "AGENT_ERROR");

    private final AiQueryProperties props;
    private final AiQueryAgentClient agent;
    private final AiQueryMessageRepository messages;
    private final AiQueryConversationRepository conversations;
    private final AiQueryTransitions transitions;

    public AiQueryScanner(AiQueryProperties props, AiQueryAgentClient agent, AiQueryMessageRepository messages,
            AiQueryConversationRepository conversations, AiQueryTransitions transitions) {
        this.props = props;
        this.agent = agent;
        this.messages = messages;
        this.conversations = conversations;
        this.transitions = transitions;
    }

    @Scheduled(fixedDelayString = "${petgo.ai-query.scan-delay-ms:3000}",
            initialDelayString = "${petgo.ai-query.scan-initial-delay-ms:10000}")
    public void scheduled() {
        try {
            scan();
        } catch (RuntimeException e) {
            // 单轮失败不影响下一轮；不带异常消息（可能含 SQL 参数）
            log.error("ai-query scan failed cause={}", e.getClass().getSimpleName());
        }
    }

    /** 一轮扫描（测试直接调用）。 */
    public void scan() {
        if (!props.isEnabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(props.getTimeoutMinutes(), ChronoUnit.MINUTES);
        timeouts(cutoff);
        dispatchPending();
        pollRunning();
    }

    void timeouts(Instant cutoff) {
        for (AiQueryMessage m : messages.findStale(cutoff, PageRequest.of(0, BATCH * 4))) {
            if (transitions.timeout(m, cutoff)) {
                log.info("ai-query job timed out messageToken={} from={}", m.getToken(), m.getStatus());
            }
        }
    }

    void dispatchPending() {
        List<AiQueryMessage> pending = messages.findByStatusOrderByIdAsc(AiQueryStatus.PENDING, PageRequest.of(0, BATCH));
        if (pending.isEmpty()) {
            return;
        }
        java.util.Map<Long, AiQueryConversation> byId = conversations
                .findAllById(pending.stream().map(AiQueryMessage::getConversationId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(AiQueryConversation::getId, c -> c));
        for (AiQueryMessage m : pending) {
            AiQueryConversation c = byId.get(m.getConversationId());
            if (c == null) {
                continue; // 对话已被清理（级联删除中），忽略
            }
            SubmitOutcome r = agent.submit(m.getToken(), c.getToken(), m.getQuestion(), c.getLanguage().code(),
                    c.getAgentSessionId());
            switch (r) {
                case ACCEPTED -> {
                    if (transitions.running(m)) {
                        log.info("ai-query job dispatched messageToken={}", m.getToken());
                    }
                }
                case REJECTED -> transitions.failed(m, AiQueryStatus.PENDING, ERR_AGENT_REJECTED, null);
                case RETRY -> {
                    // 保持 PENDING：agent 排队已满或不可达，下轮再试；本轮不再继续提交
                    return;
                }
            }
        }
    }

    void pollRunning() {
        for (AiQueryMessage m : messages.findByStatusOrderByIdAsc(AiQueryStatus.RUNNING, PageRequest.of(0, BATCH))) {
            PollOutcome r = agent.poll(m.getToken());
            if (r instanceof PollOutcome.Unknown) {
                if (transitions.failed(m, AiQueryStatus.RUNNING, ERR_AGENT_LOST, null)) {
                    log.warn("ai-query job unknown to agent, marked failed messageToken={}", m.getToken());
                }
            } else if (r instanceof PollOutcome.Found f) {
                settle(m, f.job());
            } else {
                // Unreachable：agent 不可达时其余任务也一样，本轮停止，避免逐个等超时拖住调度线程；到时由超时兜底
                return;
            }
        }
    }

    private void settle(AiQueryMessage m, AgentJob job) {
        if (job.done()) {
            if (transitions.done(m, job)) {
                log.info("ai-query job done messageToken={} secs={}", m.getToken(), job.secs());
            }
        } else if (job.failed()) {
            String code = AGENT_ERROR_CODES.contains(job.errorCode()) ? job.errorCode() : "AGENT_ERROR";
            if (transitions.failed(m, AiQueryStatus.RUNNING, code, job)) {
                if (ERR_AUTH_FAILED.equals(code)) {
                    // 订阅 token 失效：全部查询都会失败，需要管理员处理（spec §11）
                    log.error("ai-query agent authorization failed messageToken={}", m.getToken());
                } else {
                    log.warn("ai-query job failed messageToken={} errorCode={}", m.getToken(), code);
                }
            }
        }
        // QUEUED / RUNNING：继续等
    }
}
