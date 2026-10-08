package com.tailtopia.tailsonality.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Tailsonality 测试结果（V1.3.2 batch-a Story 2.1 · 表 {@code tailsonality_results}）。
 *
 * <p>一次提交一行；重测再插一行，旧行不动。{@link #unlockedAt} 只由 Story 3.2 的解锁发放置位。
 *
 * <p>{@code type_code CHAR(4)} / {@code energy CHAR(1)} 显式按 {@link SqlTypes#CHAR} 映射，
 * 与库里的 {@code bpchar} 一致（{@code ddl-auto=validate}）。
 */
@Entity(name = "TailsonalityResult")
@Table(name = "tailsonality_results")
public class TailsonalityResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, updatable = false, length = 32)
    private String publicToken;

    @Column(name = "pet_profile_id", nullable = false, updatable = false)
    private Long petProfileId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_set", nullable = false, updatable = false, length = 16)
    private TailsonalityQuestionSet questionSet;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers", nullable = false, updatable = false)
    private Map<String, Integer> answers = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "type_code", nullable = false, updatable = false, length = 4)
    private String typeCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "energy", nullable = false, updatable = false, length = 1)
    private String energy;

    @Column(name = "content_version", nullable = false, updatable = false)
    private int contentVersion;

    @Column(name = "unlocked_at")
    private Instant unlockedAt;

    /** 配型单独解锁时刻（2026-10-09）；只由 TS_MATCH 发放置位。配型可看见 {@link #matchUnlocked()}。 */
    @Column(name = "match_unlocked_at")
    private Instant matchUnlockedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TailsonalityResult() {
    }

    public static TailsonalityResult create(String publicToken, long petProfileId, long userId,
            TailsonalityQuestionSet questionSet, Map<String, Integer> answers, TailsonalityCode code,
            int contentVersion, Instant createdAt) {
        TailsonalityResult r = new TailsonalityResult();
        r.publicToken = publicToken;
        r.petProfileId = petProfileId;
        r.userId = userId;
        r.questionSet = questionSet;
        r.answers = new LinkedHashMap<>(answers);
        r.typeCode = code.letters();
        r.energy = code.energy();
        r.contentVersion = contentVersion;
        r.createdAt = createdAt;
        return r;
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public Long getUserId() {
        return userId;
    }

    public TailsonalityQuestionSet getQuestionSet() {
        return questionSet;
    }

    public Map<String, Integer> getAnswers() {
        return java.util.Collections.unmodifiableMap(answers);
    }

    public String getTypeCode() {
        return typeCode;
    }

    public String getEnergy() {
        return energy;
    }

    public int getContentVersion() {
        return contentVersion;
    }

    public Instant getUnlockedAt() {
        return unlockedAt;
    }

    public Instant getMatchUnlockedAt() {
        return matchUnlockedAt;
    }

    /** 配型可看：完整解读已解锁（含配型），或单独买过配型。 */
    public boolean matchUnlocked() {
        return unlockedAt != null || matchUnlockedAt != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TailsonalityCode code() {
        return new TailsonalityCode(typeCode, energy);
    }
}
