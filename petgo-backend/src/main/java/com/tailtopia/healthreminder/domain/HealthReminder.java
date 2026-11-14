package com.tailtopia.healthreminder.domain;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.VaccinePreset;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * 一条提醒（{@code health_reminders}，V1.5.0 Story 2.1，架构增量 §1.3）：某宠物某日的一个计划项。日期列全是 WIB 日历日。
 * 🔒 快照字段（疫苗名 / custom 名）属健康数据，禁止进日志。
 */
@Entity
@Table(name = "health_reminders")
public class HealthReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_profile_id", nullable = false)
    private Long petProfileId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "source_record_id")
    private Long sourceRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_kind", nullable = false, length = 32)
    private ReminderKind reminderKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_point", nullable = false, length = 16)
    private PushPoint pushPoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    private ReminderOrigin origin;

    @Column(name = "next_date_ref")
    private LocalDate nextDateRef;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "send_date", nullable = false)
    private LocalDate sendDate;

    @Column(name = "deferred_days", nullable = false)
    private Integer deferredDays = 0;

    @Column(name = "tier", nullable = false)
    private Integer tier;

    @Column(name = "movable", nullable = false)
    private boolean movable;

    @Enumerated(EnumType.STRING)
    @Column(name = "due_vaccine_preset", length = 24)
    private VaccinePreset dueVaccinePreset;

    @Column(name = "due_vaccine_name", length = 30)
    private String dueVaccineName;

    @Column(name = "due_dose")
    private Integer dueDose;

    @Enumerated(EnumType.STRING)
    @Column(name = "deworm_type", length = 16)
    private DewormType dewormType;

    @Column(name = "custom_name", length = 20)
    private String customName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReminderStatus status = ReminderStatus.PENDING;

    @Convert(converter = CancelReasonConverter.class)
    @Column(name = "cancel_reason", length = 24)
    private CancelReason cancelReason;

    @Column(name = "displaced_by", length = 32)
    private String displacedBy;

    @Column(name = "health_notification_id")
    private Long healthNotificationId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "satisfied_at")
    private Instant satisfiedAt;

    @Column(name = "satisfied_by_record_id")
    private Long satisfiedByRecordId;

    @Column(name = "queue_position")
    private Integer queuePosition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HealthReminder() {
    }

    /** 新建一条 PENDING 行（send_date 初始 = due_date）。 */
    public static HealthReminder pending(long petProfileId, long ownerId, Long sourceRecordId, ReminderKind kind,
            PushPoint point, ReminderOrigin origin, LocalDate nextDateRef, LocalDate dueDate) {
        HealthReminder r = new HealthReminder();
        r.petProfileId = petProfileId;
        r.ownerId = ownerId;
        r.sourceRecordId = sourceRecordId;
        r.reminderKind = kind;
        r.pushPoint = point;
        r.origin = origin;
        r.nextDateRef = nextDateRef;
        r.dueDate = dueDate;
        r.sendDate = dueDate;
        r.tier = kind.tier();
        r.movable = kind.movable();
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

    /** 取消（只对 PENDING 生效）。 */
    public void cancel(CancelReason reason, String displacedBy) {
        this.status = ReminderStatus.CANCELLED;
        this.cancelReason = reason;
        this.displacedBy = displacedBy;
    }

    public void markSent(long healthNotificationId, Instant sentAt) {
        this.status = ReminderStatus.SENT;
        this.healthNotificationId = healthNotificationId;
        this.sentAt = sentAt;
    }

    public void markSatisfied(Instant at, Long byRecordId) {
        this.satisfiedAt = at;
        this.satisfiedByRecordId = byRecordId;
    }

    /** 顺延到 {@code day}（deferred_days = day − due_date）。 */
    public void deferTo(LocalDate day) {
        this.sendDate = day;
        this.deferredDays = (int) java.time.temporal.ChronoUnit.DAYS.between(dueDate, day);
    }

    /** 挪动计划发送日但不计入顺延天数（怀孕挂起、排队行每日后移）。 */
    public void moveSendDate(LocalDate day) {
        this.sendDate = day;
    }

    public void setSnapshot(VaccinePreset preset, String vaccineName, Integer dose, DewormType deworm,
            String custom) {
        this.dueVaccinePreset = preset;
        this.dueVaccineName = vaccineName;
        this.dueDose = dose;
        this.dewormType = deworm;
        this.customName = custom;
    }

    public void setQueuePosition(Integer queuePosition) {
        this.queuePosition = queuePosition;
    }

    public void setMovable(boolean movable) {
        this.movable = movable;
    }

    public Long getId() {
        return id;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public Long getSourceRecordId() {
        return sourceRecordId;
    }

    public ReminderKind getReminderKind() {
        return reminderKind;
    }

    public PushPoint getPushPoint() {
        return pushPoint;
    }

    public ReminderOrigin getOrigin() {
        return origin;
    }

    public LocalDate getNextDateRef() {
        return nextDateRef;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getSendDate() {
        return sendDate;
    }

    public int getDeferredDays() {
        return deferredDays == null ? 0 : deferredDays;
    }

    public int getTier() {
        return tier;
    }

    public boolean isMovable() {
        return movable;
    }

    public VaccinePreset getDueVaccinePreset() {
        return dueVaccinePreset;
    }

    public String getDueVaccineName() {
        return dueVaccineName;
    }

    public Integer getDueDose() {
        return dueDose;
    }

    public DewormType getDewormType() {
        return dewormType;
    }

    public String getCustomName() {
        return customName;
    }

    public ReminderStatus getStatus() {
        return status;
    }

    public CancelReason getCancelReason() {
        return cancelReason;
    }

    public String getDisplacedBy() {
        return displacedBy;
    }

    public Long getHealthNotificationId() {
        return healthNotificationId;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getSatisfiedAt() {
        return satisfiedAt;
    }

    public Long getSatisfiedByRecordId() {
        return satisfiedByRecordId;
    }

    public Integer getQueuePosition() {
        return queuePosition;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
