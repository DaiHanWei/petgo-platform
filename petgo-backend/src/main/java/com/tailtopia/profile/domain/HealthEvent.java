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
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 健康事件（Story 2.5 创建 {@code health_events} 表）。问诊存档承接 → 进成长时间线。
 *
 * <p>{@code sourceRef} 为幂等键（一次问诊一条决策）。{@code imageKeys} 存私密桶②自有 key
 * （展示走签名 URL，**绝不存会过期的 IM URL**）。症状/建议属健康数据，日志严禁落明文。
 */
@Entity
@Table(name = "health_events")
public class HealthEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private HealthSourceType sourceType;

    @Column(name = "source_ref", nullable = false, length = 64)
    private String sourceRef;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "symptom_summary")
    private String symptomSummary;

    @Column(name = "ai_level", length = 8)
    private String aiLevel;

    @Column(name = "advice_summary")
    private String adviceSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_keys")
    private List<String> imageKeys;

    @Enumerated(EnumType.STRING)
    @Column(name = "archive_decision", nullable = false, length = 8)
    private ArchiveDecision archiveDecision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected HealthEvent() {
    }

    public static HealthEvent archived(Long petId, HealthSourceType sourceType, String sourceRef,
            LocalDate eventDate, String symptomSummary, String aiLevel, String adviceSummary,
            List<String> imageKeys) {
        HealthEvent e = base(petId, sourceType, sourceRef, eventDate, ArchiveDecision.ARCHIVED);
        e.symptomSummary = symptomSummary;
        e.aiLevel = aiLevel;
        e.adviceSummary = adviceSummary;
        e.imageKeys = imageKeys;
        return e;
    }

    public static HealthEvent skipped(Long petId, HealthSourceType sourceType, String sourceRef,
            LocalDate eventDate) {
        return base(petId, sourceType, sourceRef, eventDate, ArchiveDecision.SKIPPED);
    }

    private static HealthEvent base(Long petId, HealthSourceType sourceType, String sourceRef,
            LocalDate eventDate, ArchiveDecision decision) {
        HealthEvent e = new HealthEvent();
        e.petId = petId;
        e.sourceType = sourceType;
        e.sourceRef = sourceRef;
        e.eventDate = eventDate;
        e.archiveDecision = decision;
        return e;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPetId() {
        return petId;
    }

    public HealthSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public String getSymptomSummary() {
        return symptomSummary;
    }

    public String getAiLevel() {
        return aiLevel;
    }

    public String getAdviceSummary() {
        return adviceSummary;
    }

    public List<String> getImageKeys() {
        return imageKeys;
    }

    public ArchiveDecision getArchiveDecision() {
        return archiveDecision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
