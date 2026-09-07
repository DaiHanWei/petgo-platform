package com.tailtopia.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 生命周期推送去重标记（留存运营作战手册 · 抓手 1）。唯一约束 {@code (user_id, push_kind, node_key)}
 * 是「该节点仅推一次」的<b>单一事实源</b> —— 禁用 Redis/MQ 当去重源。
 *
 * <p>与 Story 6.7 的 {@code scheduled_push_marks} 并列而非复用：那张表的粒度是 pet_profile，
 * 覆盖不了「注册了但根本没建档」这一层 —— 而那层恰好是 557 人 / 85.8% 的大头，是召回的主战场。
 *
 * <p>{@code variant} 只为运营复盘留痕（手册每周「召回漏斗」要按分层看转化），不参与去重。
 */
@Entity
@Table(name = "lifecycle_push_marks")
public class LifecyclePushMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "push_kind", nullable = false, length = 32)
    private String pushKind;

    @Column(name = "node_key", nullable = false, length = 32)
    private String nodeKey;

    @Column(name = "variant", nullable = false, length = 32)
    private String variant;

    @Column(name = "pushed_at", nullable = false, updatable = false)
    private Instant pushedAt;

    protected LifecyclePushMark() {
    }

    public static LifecyclePushMark of(long userId, String pushKind, String nodeKey, String variant) {
        LifecyclePushMark m = new LifecyclePushMark();
        m.userId = userId;
        m.pushKind = pushKind;
        m.nodeKey = nodeKey;
        m.variant = variant;
        return m;
    }

    @PrePersist
    void onCreate() {
        this.pushedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getPushKind() {
        return pushKind;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public String getVariant() {
        return variant;
    }

    public Instant getPushedAt() {
        return pushedAt;
    }
}
