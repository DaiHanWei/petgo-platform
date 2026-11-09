package com.tailtopia.admin.aiquery.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一轮问答（spec §5.1）。用户提交时只在事务内写 {@code PENDING}；之后的状态只由扫描器经带旧状态条件的
 * UPDATE 推进（{@code AiQueryMessageRepository}），实体本身不提供改状态的方法，避免脏写覆盖。
 *
 * <p>🔴 {@link #question} 与 {@link #answerMd} 可能含 PII：不进审计、不进日志、不出现在 {@code toString}。
 */
@Entity
@Table(name = "ai_query_messages")
public class AiQueryMessage {

    public static final int QUESTION_MAX = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 64)
    private String token;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private long conversationId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String question;

    @Column(name = "answer_md", insertable = false, updatable = false, columnDefinition = "text")
    private String answerMd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private AiQueryStatus status;

    @Column(name = "error_code", insertable = false, updatable = false, length = 32)
    private String errorCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "files", nullable = false, updatable = false)
    private List<AiQueryFile> files = new ArrayList<>();

    @Column(name = "agent_secs", insertable = false, updatable = false)
    private Integer agentSecs;

    @Column(name = "agent_turns", insertable = false, updatable = false)
    private Integer agentTurns;

    @Column(name = "cost_usd", insertable = false, updatable = false, precision = 10, scale = 4)
    private BigDecimal costUsd;

    @Column(name = "submitted_at", insertable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "finished_at", insertable = false, updatable = false)
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    protected AiQueryMessage() {
    }

    public static AiQueryMessage pending(String token, long conversationId, String question) {
        AiQueryMessage m = new AiQueryMessage();
        m.token = token;
        m.conversationId = conversationId;
        m.question = question;
        m.status = AiQueryStatus.PENDING;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        m.createdAt = now;
        m.updatedAt = now;
        return m;
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public long getConversationId() {
        return conversationId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswerMd() {
        return answerMd;
    }

    public AiQueryStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<AiQueryFile> getFiles() {
        return files == null ? List.of() : files;
    }

    public Integer getAgentSecs() {
        return agentSecs;
    }

    public Integer getAgentTurns() {
        return agentTurns;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isUnfinished() {
        return status != null && status.isUnfinished();
    }

    @Override
    public String toString() {
        return "AiQueryMessage{token=" + token + ", status=" + status + ", errorCode=" + errorCode + "}";
    }
}
