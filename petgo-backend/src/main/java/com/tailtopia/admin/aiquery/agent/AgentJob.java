package com.tailtopia.admin.aiquery.agent;

import com.tailtopia.admin.aiquery.domain.AiQueryFile;
import java.math.BigDecimal;
import java.util.List;

/**
 * agent {@code GET /jobs/{jobToken}} 的返回体（契约见 ai-query-agent Story 1.1，字段名不可改）。
 * {@code status}：QUEUED | RUNNING | DONE | FAILED；{@code errorCode}：AGENT_RATE_LIMITED | AGENT_AUTH_FAILED | AGENT_TIMEOUT | AGENT_ERROR。
 */
public record AgentJob(String status, String answerMd, String sessionId, List<AgentFile> files,
        Integer secs, Integer turns, BigDecimal costUsd, String errorCode) {

    /** agent 文件清单项；size 可能缺省，用包装类型避免反序列化失败。 */
    public record AgentFile(String name, Long size) {
    }

    public boolean done() {
        return "DONE".equals(status);
    }

    public boolean failed() {
        return "FAILED".equals(status);
    }

    public List<AiQueryFile> filesOrEmpty() {
        return files == null ? List.of() : files.stream()
                .filter(f -> f != null)
                .map(f -> new AiQueryFile(f.name(), f.size() == null ? 0 : f.size()))
                .toList();
    }
}
