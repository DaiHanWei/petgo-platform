package com.tailtopia.social.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 隐藏关系（Story 1.1，FR-94 / FR-58）——「某个账号的内容不再展示给某个用户」这件事本身。
 *
 * <p>唯一键是<b>三元</b> {@code (holder_id, target_id, source)}（{@code uq_user_hide_relations_holder_target_source}），
 * 不是二元 —— <b>幂等只在同一来源之间成立</b>：已存在 {@link HideSource#REPORT} 行时再发起主动拉黑，
 * 必须照常新增 {@link HideSource#BLOCK} 行（决策 C-91）。
 *
 * <p>⚠️ {@code createdAt} 是黑名单页的排序依据，<b>重复拉黑不得刷新它</b>（否则用户反复点击会把自己顶到列表最前）；
 * 举报侧写入 {@code REPORT} 行时也<b>不得触碰同 (holder, target) 下的 BLOCK 行</b>——两条关系是彼此独立的行。
 */
@Entity
@Table(name = "user_hide_relations")
public class UserHideRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 不想看见对方的人（users.id）。 */
    @Column(name = "holder_id", nullable = false)
    private Long holderId;

    /** 被隐藏的人（users.id）。 */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private HideSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserHideRelation() {
    }

    public static UserHideRelation create(long holderId, long targetId, HideSource source) {
        UserHideRelation r = new UserHideRelation();
        r.holderId = holderId;
        r.targetId = targetId;
        r.source = source;
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

    public Long getHolderId() {
        return holderId;
    }

    public Long getTargetId() {
        return targetId;
    }

    public HideSource getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
