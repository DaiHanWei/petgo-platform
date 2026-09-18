package com.tailtopia.mention.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * @ 候选集的一行（V1.3.0 batch-b1 Story 3.1 · AD-10）：
 * 「{@code ownerId} 最近与 {@code candidateId} 互动过」。
 *
 * <h2>⚠️ 这张表是派生物，不是真值来源</h2>
 * 它是 comments / content_likes 的一份**倒排索引**，整表清空也只是让候选集暂时变空 ——
 * 重新互动一次就会回来。因此它<b>不参与任何事务保证</b>：写入走 {@code @Async}，失败只记一行日志。
 *
 * <h2>⚠️ 实体只用于「读」</h2>
 * 写入走仓储里的原生 {@code ON CONFLICT DO UPDATE}（见
 * {@code MentionCandidateRepository#touch}）—— 用 JPA 的 find-then-save 会在并发互动下
 * 撞唯一约束，而那个异常穿出来会把调用方的事务标成 rollback-only。
 */
@Entity
@Table(name = "mention_candidates")
public class MentionCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 这份候选集属于谁（输入 @ 的那个人）。 */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 候选人。 */
    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    /**
     * 最近一次互动时间 —— <b>排序键，也是裁剪时的淘汰依据</b>。
     *
     * <p>⚠️ 不是 {@code createdAt}：一个人反复与你互动，应当一直排在前面，
     * 而不是停在第一次互动的时间上。
     */
    @Column(name = "last_interacted_at", nullable = false)
    private Instant lastInteractedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MentionCandidate() {
    }

    public Long getId() {
        return id;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public long getCandidateId() {
        return candidateId;
    }

    public Instant getLastInteractedAt() {
        return lastInteractedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
