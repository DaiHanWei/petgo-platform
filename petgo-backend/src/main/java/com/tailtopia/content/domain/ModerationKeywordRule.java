package com.tailtopia.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 分层审核词库规则（内容审核 Story 1 · 方案 §9）。表 {@code moderation_keyword_rules}（V47）。
 *
 * <p>三层：{@code L1_BLOCK}（平台强制黑名单，命中即硬拦截）、{@code L2_ADJUSTABLE}（运营可调中风险，
 * 命中作评分加权项，非硬拦截）、{@code L3_WHITELIST}（宠物场景白名单，<b>优先级最高</b>，命中豁免同步硬拦截）。
 *
 * <p>匹配方式 {@code SUBSTRING}/{@code EXACT}/{@code REGEX}，大小写不敏感由应用层归一保证（{@code Locale.ROOT}）。
 * 枚举均落 {@code varchar} + UPPER_SNAKE（命名映射链）；{@code enabled} 用 boolean（无 CHAR(1) 陷阱）。
 */
@Entity
@Table(name = "moderation_keyword_rules")
public class ModerationKeywordRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** L1_BLOCK / L2_ADJUSTABLE / L3_WHITELIST。 */
    @Column(name = "rule_kind", nullable = false, length = 16)
    private String ruleKind;

    /** SUBSTRING / REGEX / EXACT。 */
    @Column(name = "match_type", nullable = false, length = 16)
    private String matchType;

    /** 词或正则（大小写不敏感由应用层保证）。 */
    @Column(name = "pattern", nullable = false, length = 512)
    private String pattern;

    /** DRUGS/GAMBLING/PORN/POLITICS/AD_SPAM/HARASSMENT/WEAPON/PET_SAFE... */
    @Column(name = "category", nullable = false, length = 32)
    private String category;

    /** id / en / zh / ALL。 */
    @Column(name = "lang", nullable = false, length = 8)
    private String lang;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ModerationKeywordRule() {
    }

    /** 测试/内存构造：直接以字段建规则（不落库）。 */
    public static ModerationKeywordRule of(String ruleKind, String matchType, String pattern,
            String category, String lang, boolean enabled) {
        ModerationKeywordRule r = new ModerationKeywordRule();
        r.ruleKind = ruleKind;
        r.matchType = matchType;
        r.pattern = pattern;
        r.category = category;
        r.lang = lang;
        r.enabled = enabled;
        return r;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getRuleKind() {
        return ruleKind;
    }

    public String getMatchType() {
        return matchType;
    }

    public String getPattern() {
        return pattern;
    }

    public String getCategory() {
        return category;
    }

    public String getLang() {
        return lang;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
