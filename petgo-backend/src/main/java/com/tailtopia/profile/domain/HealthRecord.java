package com.tailtopia.profile.domain;

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
import java.time.LocalDate;

/**
 * 结构化健康记录（Story 7.1 创建 {@code health_records} 表，FR-45/45A）。用户手动录入
 * （疫苗/驱虫/月经/绝育/自定义）。区别 {@code health_events}（问诊存档，非手动 CRUD）。
 *
 * <p>健康数据=PII：日志严禁落 type/name/note/date 明文。档案删除级联硬删（PDP）。
 */
@Entity
@Table(name = "health_records")
public class HealthRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_profile_id", nullable = false)
    private Long petProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private HealthRecordType type;

    @Column(name = "custom_name", length = 20)
    private String customName;

    @Column(name = "vaccine_name", length = 30)
    private String vaccineName;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "note", length = 100)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HealthRecord() {
    }

    public static HealthRecord create(long petProfileId, HealthRecordType type, String customName,
            String vaccineName, LocalDate eventDate, String note) {
        HealthRecord r = new HealthRecord();
        r.petProfileId = petProfileId;
        r.type = type;
        r.customName = customName;
        r.vaccineName = vaccineName;
        r.eventDate = eventDate;
        r.note = note;
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

    public Long getPetProfileId() {
        return petProfileId;
    }

    public HealthRecordType getType() {
        return type;
    }

    public void setType(HealthRecordType type) {
        this.type = type;
    }

    public String getCustomName() {
        return customName;
    }

    public void setCustomName(String customName) {
        this.customName = customName;
    }

    public String getVaccineName() {
        return vaccineName;
    }

    public void setVaccineName(String vaccineName) {
        this.vaccineName = vaccineName;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDate eventDate) {
        this.eventDate = eventDate;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
