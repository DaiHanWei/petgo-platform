package com.tailtopia.admin.aiquery.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** AI 查数对话（spec §5.1）。只对创建它的后台账号可见（含超管，spec §5.2）。 */
@Entity
@Table(name = "ai_query_conversations")
public class AiQueryConversation {

    /** 标题取问题前 80 字。 */
    public static final int TITLE_MAX = 80;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 64)
    private String token;

    @Column(name = "admin_account_id", nullable = false, updatable = false)
    private long adminAccountId;

    @Column(nullable = false, length = 80)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private AiQueryLanguage language;

    @Column(name = "agent_session_id", length = 128)
    private String agentSessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiQueryConversation() {
    }

    public static AiQueryConversation start(String token, long adminAccountId, String question, AiQueryLanguage language) {
        AiQueryConversation c = new AiQueryConversation();
        c.token = token;
        c.adminAccountId = adminAccountId;
        c.title = titleOf(question);
        c.language = language;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    /** 问题前 80 字（按 Unicode 码点，不切断代理对）；换行压成空格。 */
    static String titleOf(String question) {
        String flat = question.strip().replaceAll("\\s+", " ");
        int[] cps = flat.codePoints().limit(TITLE_MAX).toArray();
        return new String(cps, 0, cps.length);
    }

    public void touch() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public long getAdminAccountId() {
        return adminAccountId;
    }

    public String getTitle() {
        return title;
    }

    public AiQueryLanguage getLanguage() {
        return language;
    }

    public String getAgentSessionId() {
        return agentSessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
