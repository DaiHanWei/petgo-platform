package com.tailtopia.consult.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 问诊评分（Story 5.6，FR-33）。1-5 星必填 + ≤100 字选填。<b>仅运营可见</b>。
 *
 * <p>注销时匿名化保留（决策 D1：剥 {@code user_id} PII，留评级/评分供运营 FR-33）。每会话至多一条评分。
 */
@Entity
@Table(name = "consult_ratings")
public class ConsultRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "vet_id", nullable = false)
    private Long vetId;

    /** 注销时匿名化置 NULL（决策 D1）。 */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "stars", nullable = false)
    private int stars;

    @Column(name = "comment", length = 100)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConsultRating() {
    }

    public static ConsultRating of(long sessionId, long vetId, long userId, int stars, String comment) {
        ConsultRating r = new ConsultRating();
        r.sessionId = sessionId;
        r.vetId = vetId;
        r.userId = userId;
        r.stars = stars;
        r.comment = comment;
        return r;
    }

    /** 注销匿名化（Story 7.3，决策 D1）：剥 user PII（解关联 userId），保留 stars/comment/vetId 供运营 FR-33。 */
    public void anonymize() {
        this.userId = null;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getVetId() {
        return vetId;
    }

    public int getStars() {
        return stars;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
