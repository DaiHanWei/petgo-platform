package com.tailtopia.admin.aiquery.service;

import com.tailtopia.admin.aiquery.agent.AgentJob;
import com.tailtopia.admin.aiquery.domain.AiQueryFile;
import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import com.tailtopia.admin.aiquery.domain.AiQueryStatus;
import com.tailtopia.admin.aiquery.repository.AiQueryConversationRepository;
import com.tailtopia.admin.aiquery.repository.AiQueryMessageRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 任务状态迁移（spec §4.1）。每个方法一个事务，<b>一律带旧状态条件</b>：返回 {@code false} 表示状态已被别处推进
 * （重复处理 / 并发），调用方据此不再做后续动作，不会重复派发或覆盖结果。
 */
@Service
public class AiQueryTransitions {

    /** 一轮结束时消息与对话同事务更新：对话记下 Claude 会话 id、刷新最后更新时间。 */
    private final AiQueryMessageRepository messages;
    private final AiQueryConversationRepository conversations;
    private final ObjectMapper json;

    public AiQueryTransitions(AiQueryMessageRepository messages, AiQueryConversationRepository conversations,
            ObjectMapper json) {
        this.messages = messages;
        this.conversations = conversations;
        this.json = json;
    }

    @Transactional
    public boolean running(AiQueryMessage m) {
        return messages.markRunning(m.getId()) == 1;
    }

    @Transactional
    public boolean done(AiQueryMessage m, AgentJob job) {
        String files = json.writeValueAsString(sanitize(job.filesOrEmpty()));
        boolean won = messages.markDone(m.getId(), job.answerMd() == null ? "" : job.answerMd(), files,
                job.secs(), job.turns(), cost(job.costUsd())) == 1;
        if (won) {
            conversations.finishTurn(m.getConversationId(), blankToNull(job.sessionId()));
        }
        return won;
    }

    /** {@code job} 可为空（agent 不认识该任务 / 提交被拒）。 */
    @Transactional
    public boolean failed(AiQueryMessage m, AiQueryStatus from, String errorCode, AgentJob job) {
        boolean won = messages.markFailed(m.getId(), from.name(), errorCode,
                job == null ? null : job.secs(), job == null ? null : job.turns(),
                job == null ? null : cost(job.costUsd())) == 1;
        if (won) {
            conversations.finishTurn(m.getConversationId(), job == null ? null : blankToNull(job.sessionId()));
        }
        return won;
    }

    @Transactional
    public boolean timeout(AiQueryMessage m, Instant cutoff) {
        boolean won = messages.markTimeout(m.getId(), cutoff) == 1;
        if (won) {
            conversations.finishTurn(m.getConversationId(), null);
        }
        return won;
    }

    /** 只保留下载时也能通过校验的文件名（同一条规则）；大小非负。 */
    static List<AiQueryFile> sanitize(List<AiQueryFile> files) {
        return files.stream()
                .filter(f -> f != null && AiQueryQueries.isSafeFileName(f.name()))
                .map(f -> new AiQueryFile(f.name(), Math.max(0, f.size())))
                .toList();
    }

    /** numeric(10,4)：超出范围的估算值丢弃（订阅方式下 cost 只是估算，spec §11）。 */
    static BigDecimal cost(BigDecimal c) {
        if (c == null) {
            return null;
        }
        BigDecimal r = c.setScale(4, RoundingMode.HALF_UP);
        return r.abs().compareTo(new BigDecimal("999999.9999")) > 0 ? null : r;
    }

    /** Claude 会话 id：只接受短的字母数字连字符（列宽 128）；异常值丢弃，不让一条坏数据回滚整轮结果。 */
    static String blankToNull(String s) {
        return s == null || !s.matches("[A-Za-z0-9-]{1,128}") ? null : s;
    }
}
