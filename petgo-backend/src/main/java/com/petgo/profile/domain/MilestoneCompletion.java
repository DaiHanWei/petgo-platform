package com.petgo.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 里程碑完成记录（Story 8.1，{@code milestone_completions}）。仅记已完成项；
 * {@code pet_milestone_id} 唯一约束 → 自动完成幂等、不可撤销；{@code linked_content_id} partial-unique
 * → 一条成长日历内容至多关联一个里程碑（FR-42 用户打卡，8.4）。
 */
@Entity
@Table(name = "milestone_completions")
public class MilestoneCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_milestone_id", nullable = false)
    private Long petMilestoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 24)
    private MilestoneCompletionSource source;

    /** 用户打卡关联的成长日历内容 id（系统自动类为 null）。 */
    @Column(name = "linked_content_id")
    private Long linkedContentId;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    protected MilestoneCompletion() {
    }

    public static MilestoneCompletion of(long petMilestoneId, MilestoneCompletionSource source,
            Long linkedContentId) {
        MilestoneCompletion c = new MilestoneCompletion();
        c.petMilestoneId = petMilestoneId;
        c.source = source;
        c.linkedContentId = linkedContentId;
        return c;
    }

    @PrePersist
    void onCreate() {
        if (this.completedAt == null) {
            this.completedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPetMilestoneId() {
        return petMilestoneId;
    }

    public MilestoneCompletionSource getSource() {
        return source;
    }

    public Long getLinkedContentId() {
        return linkedContentId;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
