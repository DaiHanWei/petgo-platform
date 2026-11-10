package com.tailtopia.admin.aiquery.service;

import com.tailtopia.admin.aiquery.agent.AiQueryAgentClient;
import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryConversation;
import com.tailtopia.admin.aiquery.repository.AiQueryConversationRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 每日清理（spec §5.7，Story 2.2）：最后更新超过 {@code AI_QUERY_RETENTION_DAYS}（默认 30）天的对话，
 * <b>先</b>调 agent {@code DELETE /conversations/{token}} 删除导出文件与 Claude 会话记录，<b>成功后</b>再删库
 * （消息级联删除）。agent 不可达则跳过该对话、下轮重试，不删库。审计记录不受影响（长期保留、不含问题原文）。
 *
 * <p>与 {@code AI_QUERY_MODE} 无关：保留期是隐私要求，功能关闭期间也照常清理。按规划「agent 不可达则跳过」，
 * agent 未配置时同样跳过并记 warn（导出文件与会话记录在 agent 上，不能只删库留下孤儿数据）。
 *
 * <p>按游标遍历全部过期对话（每轮最多 {@link #MAX_PER_RUN} 个）：反复失败的对话不会堵住后面的。
 */
@Component
public class AiQueryCleanup {

    private static final Logger log = LoggerFactory.getLogger(AiQueryCleanup.class);
    static final int BATCH = 200;
    static final int MAX_PER_RUN = 5_000;

    private final AiQueryProperties props;
    private final AiQueryAgentClient agent;
    private final AiQueryConversationRepository conversations;
    private final TransactionTemplate tx;

    public AiQueryCleanup(AiQueryProperties props, AiQueryAgentClient agent,
            AiQueryConversationRepository conversations, TransactionTemplate tx) {
        this.props = props;
        this.agent = agent;
        this.conversations = conversations;
        this.tx = tx;
    }

    /** 默认每天 19:40 UTC（02:40 WIB）。测试里设为 "-" 关闭，由用例直接调用 {@link #run()}。 */
    @Scheduled(cron = "${petgo.ai-query.cleanup-cron:0 40 19 * * *}", zone = "UTC")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("ai-query cleanup failed cause={}", e.getClass().getSimpleName());
        }
    }

    /** 一轮清理，返回删除的对话数。 */
    public int run() {
        Instant cutoff = props.notBefore();
        int deleted = 0;
        int skipped = 0;
        int seen = 0;
        Instant afterU = Instant.EPOCH;
        long afterId = 0;
        while (seen < MAX_PER_RUN) {
            var page = conversations.findExpiredAfter(cutoff, afterU, afterId, PageRequest.of(0, BATCH));
            if (page.isEmpty()) {
                break;
            }
            if (!props.agentConfigured()) {
                log.warn("ai-query cleanup skipped: agent not configured, expired conversations remain");
                return 0;
            }
            for (AiQueryConversation c : page) {
                seen++;
                afterU = c.getUpdatedAt();
                afterId = c.getId();
                if (!agent.deleteConversation(c.getToken())) {
                    skipped++;
                    continue;
                }
                Integer n = tx.execute(s -> conversations.deleteIfExpired(c.getId(), cutoff));
                deleted += n == null ? 0 : n;
            }
        }
        if (deleted > 0 || skipped > 0) {
            log.info("ai-query cleanup deleted={} skipped={}", deleted, skipped);
        }
        return deleted;
    }
}
