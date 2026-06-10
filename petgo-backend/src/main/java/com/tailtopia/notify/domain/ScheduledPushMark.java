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
 * 定时类系统推送去重标记（Story 6.7，决策 F5）。唯一约束 {@code (pet_profile_id, push_kind, node_key)}
 * 是「该节点仅推一次」的单一事实源——禁用 Redis/MQ 当去重源。
 *
 * <p>{@code nodeKey} 语义：生日=年份(按年去重)；纪念日=节点天数(30/100/365)；里程碑节点=节点 id。
 */
@Entity
@Table(name = "scheduled_push_marks")
public class ScheduledPushMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_profile_id", nullable = false)
    private Long petProfileId;

    @Column(name = "push_kind", nullable = false, length = 32)
    private String pushKind;

    @Column(name = "node_key", nullable = false, length = 32)
    private String nodeKey;

    @Column(name = "pushed_at", nullable = false, updatable = false)
    private Instant pushedAt;

    protected ScheduledPushMark() {
    }

    public static ScheduledPushMark of(long petProfileId, String pushKind, String nodeKey) {
        ScheduledPushMark m = new ScheduledPushMark();
        m.petProfileId = petProfileId;
        m.pushKind = pushKind;
        m.nodeKey = nodeKey;
        return m;
    }

    @PrePersist
    void onCreate() {
        this.pushedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public String getPushKind() {
        return pushKind;
    }

    public String getNodeKey() {
        return nodeKey;
    }
}
