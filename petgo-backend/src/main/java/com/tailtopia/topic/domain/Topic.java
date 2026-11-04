package com.tailtopia.topic.domain;

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

/**
 * 话题（V1.4.2 social-topic · Story 4.1 · D-2）。表 {@code topics}（迁移 {@code V20261104_2050}）。
 *
 * <p>🔴 <b>对外唯一标识是 {@link #getNameNorm() 标准名}</b>，{@code id} 只在进程内（事件 {@code topicIds}、关联表）使用，
 * 绝不出现在任何响应体里（护栏：不暴露自增 id）。
 *
 * <p>用户话题的新建一律走 {@code TopicService.attach} 的原生 {@code INSERT … ON CONFLICT DO NOTHING}（并发安全），
 * 不经本实体 {@code save}；{@code postCount} / {@code heatScore} 只由 4.2 热度任务重算写入。
 */
@Entity
@Table(name = "topics")
public class Topic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 显示写法（用户话题 = 首次出现写法；官方 = 后台填写）。已存在的话题发帖时一律不改。 */
    @Column(name = "name_display", nullable = false, length = 30)
    private String nameDisplay;

    /** 标准名：{@link TopicNameRules#normalize}。唯一。 */
    @Column(name = "name_norm", nullable = false, length = 30, updatable = false)
    private String nameNorm;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private TopicType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TopicStatus status;

    /** 官方话题说明（印尼语，选填）。 */
    @Column(name = "desc_id", length = 500)
    private String descId;

    /** 官方话题说明（英语，选填）。 */
    @Column(name = "desc_en", length = 500)
    private String descEn;

    /** 帖子数（4.2 热度任务每小时重算，非事务计数；R-5 接受滞后）。 */
    @Column(name = "post_count", nullable = false)
    private int postCount;

    /** 热度（4.2 热度任务每小时重算）。 */
    @Column(name = "heat_score", nullable = false, precision = 12, scale = 1)
    private BigDecimal heatScore;

    /** 首页话题行序号 1–5（6.1 维护）；NULL = 不在行内。 */
    @Column(name = "featured_order")
    private Integer featuredOrder;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected Topic() {
    }

    public Long getId() {
        return id;
    }

    public String getNameDisplay() {
        return nameDisplay;
    }

    public String getNameNorm() {
        return nameNorm;
    }

    public TopicType getType() {
        return type;
    }

    public TopicStatus getStatus() {
        return status;
    }

    public String getDescId() {
        return descId;
    }

    public String getDescEn() {
        return descEn;
    }

    public int getPostCount() {
        return postCount;
    }

    public BigDecimal getHeatScore() {
        return heatScore;
    }

    public Integer getFeaturedOrder() {
        return featuredOrder;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isOfficial() {
        return type == TopicType.OFFICIAL;
    }
}
