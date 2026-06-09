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
 * 宠物里程碑 roster 行（Story 8.1，{@code pet_milestones}）。建档时按 {@link MilestoneCatalog} 按 pet_type
 * 物化的 per-pet 清单副本，不含完成数据；完成态由 {@link MilestoneCompletion} 是否存在对应行决定。
 *
 * <p>对外标识用 {@link #code}（C-S1 等，稳定非顺序），绝不外露自增 id。归 profile 域，随档案级联删除。
 */
@Entity
@Table(name = "pet_milestones")
public class PetMilestone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_profile_id", nullable = false)
    private Long petProfileId;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 1)
    private MilestoneLevel level;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 24)
    private MilestoneTriggerType triggerType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PetMilestone() {
    }

    public static PetMilestone of(long petProfileId, MilestoneDefinition def) {
        PetMilestone m = new PetMilestone();
        m.petProfileId = petProfileId;
        m.code = def.code();
        m.level = def.level();
        m.triggerType = def.trigger();
        m.sortOrder = def.sortOrder();
        return m;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public String getCode() {
        return code;
    }

    public MilestoneLevel getLevel() {
        return level;
    }

    public MilestoneTriggerType getTriggerType() {
        return triggerType;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
