package com.tailtopia.tailsonality.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Tailsonality 角色小标佩戴（V1.3.2 Story 3.3 · 表 {@code tailsonality_badges}）。一宠一行，主键即宠物。
 *
 * <p>🔴 只能指向已解锁结果（服务层保证）。写入一律走仓库里的 upsert（{@code ON CONFLICT}），
 * 不用 {@code save()}：并发两笔同宠物解锁 / 切换不能撞主键。本实体只供读与 {@code ddl-auto=validate}。
 */
@Entity(name = "TailsonalityBadge")
@Table(name = "tailsonality_badges")
public class TailsonalityBadge {

    @Id
    @Column(name = "pet_profile_id", nullable = false, updatable = false)
    private Long petProfileId;

    @Column(name = "result_id", nullable = false)
    private Long resultId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected TailsonalityBadge() {
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public Long getResultId() {
        return resultId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
