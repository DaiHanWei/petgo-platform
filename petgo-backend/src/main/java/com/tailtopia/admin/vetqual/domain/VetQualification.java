package com.tailtopia.admin.vetqual.domain;

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
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 兽医资质（Story 2.1，AB-2H）。与 {@code vet_accounts} <b>1:1</b>（{@code vet_account_id} 唯一）。
 * Epic 2 数据地基：2.2 列表资质列、2.7 录入审核、2.8 到期阻断均依赖本表与 {@link QualificationStatus}。
 *
 * <p>证件图字段存 OSS <b>私密桶对象 key</b>（绝不存签名 URL）；签名/上传/读取在 Story 2.7。
 * 待完善阶段除 id/vet_account_id/status/时间戳外均可空。命名映射链：列 snake_case ↔ 字段 camelCase；
 * 枚举 varchar UPPER_SNAKE；时间 timestamptz UTC；{@code sipdh_expiry} 为 date（按日判到期）。
 */
@Entity
@Table(name = "vet_qualifications")
public class VetQualification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vet_account_id", nullable = false, unique = true)
    private Long vetAccountId;

    @Column(name = "ktp_no", length = 64)
    private String ktpNo;

    @Column(name = "ktp_photo_key", length = 255)
    private String ktpPhotoKey;

    @Column(name = "sipdh_no", length = 64)
    private String sipdhNo;

    @Column(name = "sipdh_issuer", length = 128)
    private String sipdhIssuer;

    @Column(name = "sipdh_expiry")
    private LocalDate sipdhExpiry;

    @Column(name = "sipdh_photo_key", length = 255)
    private String sipdhPhotoKey;

    @Column(name = "degree_photo_key", length = 255)
    private String degreePhotoKey;

    @Column(name = "profile_photo_key", length = 255)
    private String profilePhotoKey;

    @Column(name = "pdhi_photo_key", length = 255)
    private String pdhiPhotoKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specialties")
    private List<String> specialties;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private QualificationStatus status = QualificationStatus.PENDING_COMPLETION;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VetQualification() {
    }

    /** 建号后幂等创建的待完善资质行（Story 2.1/2.3）。 */
    public static VetQualification pendingFor(long vetAccountId) {
        VetQualification q = new VetQualification();
        q.vetAccountId = vetAccountId;
        q.status = QualificationStatus.PENDING_COMPLETION;
        return q;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = QualificationStatus.PENDING_COMPLETION;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ===== Story 2.7：字段写入（service 录入/续期时设置）=====
    public void setKtpNo(String v) { this.ktpNo = v; }

    public void setKtpPhotoKey(String v) { this.ktpPhotoKey = v; }

    public void setSipdhNo(String v) { this.sipdhNo = v; }

    public void setSipdhIssuer(String v) { this.sipdhIssuer = v; }

    public void setSipdhExpiry(LocalDate v) { this.sipdhExpiry = v; }

    public void setSipdhPhotoKey(String v) { this.sipdhPhotoKey = v; }

    public void setDegreePhotoKey(String v) { this.degreePhotoKey = v; }

    public void setProfilePhotoKey(String v) { this.profilePhotoKey = v; }

    public void setPdhiPhotoKey(String v) { this.pdhiPhotoKey = v; }

    public void setSpecialties(List<String> v) { this.specialties = v; }

    // ===== Story 2.7：状态机（合法迁移集中于此，非法迁移抛 AppException）=====

    /** 运营直录 / 自传后审核通过 → 已认证（清空旧驳回原因）。直录无前置态限制。 */
    public void markCertified() {
        this.status = QualificationStatus.CERTIFIED;
        this.rejectReason = null;
    }

    /** 审核通过：仅 UNDER_REVIEW 可通过。 */
    public void approve() {
        if (this.status != QualificationStatus.UNDER_REVIEW) {
            throw com.tailtopia.shared.error.AppException.conflict("仅「审核中」的资质可通过审核");
        }
        markCertified();
    }

    /** 驳回：仅 UNDER_REVIEW 可驳回，必填原因。 */
    public void reject(String reason) {
        if (this.status != QualificationStatus.UNDER_REVIEW) {
            throw com.tailtopia.shared.error.AppException.conflict("仅「审核中」的资质可驳回");
        }
        if (reason == null || reason.isBlank()) {
            throw com.tailtopia.shared.error.AppException.validation("驳回原因不能为空");
        }
        this.status = QualificationStatus.REJECTED;
        this.rejectReason = reason.trim();
    }

    /** 到期阻断（Story 2.8）：CERTIFIED/EXPIRING_SOON → EXPIRED（单向收紧；其它态忽略，由扫描器只查这两态保证）。 */
    public void markExpired() {
        if (this.status == QualificationStatus.CERTIFIED || this.status == QualificationStatus.EXPIRING_SOON) {
            this.status = QualificationStatus.EXPIRED;
        }
    }

    /** 30 天预警（Story 2.8）：仅 CERTIFIED → EXPIRING_SOON（幂等：已 EXPIRING_SOON 不变）。 */
    public void markExpiringSoon() {
        if (this.status == QualificationStatus.CERTIFIED) {
            this.status = QualificationStatus.EXPIRING_SOON;
        }
    }

    /** 续期：仅已认证/即将到期/已过期可续期回到已认证（补新证件）。 */
    public void renew() {
        if (this.status != QualificationStatus.CERTIFIED
                && this.status != QualificationStatus.EXPIRING_SOON
                && this.status != QualificationStatus.EXPIRED) {
            throw com.tailtopia.shared.error.AppException.conflict(
                    "仅「已认证/即将到期/已过期」的资质可续期；未完善/审核中/已驳回请用直录");
        }
        markCertified();
    }

    public Long getId() {
        return id;
    }

    public String getKtpNo() {
        return ktpNo;
    }

    public String getKtpPhotoKey() {
        return ktpPhotoKey;
    }

    public String getSipdhNo() {
        return sipdhNo;
    }

    public String getSipdhIssuer() {
        return sipdhIssuer;
    }

    public String getSipdhPhotoKey() {
        return sipdhPhotoKey;
    }

    public String getDegreePhotoKey() {
        return degreePhotoKey;
    }

    public String getProfilePhotoKey() {
        return profilePhotoKey;
    }

    public String getPdhiPhotoKey() {
        return pdhiPhotoKey;
    }

    public Long getVetAccountId() {
        return vetAccountId;
    }

    public QualificationStatus getStatus() {
        return status;
    }

    public LocalDate getSipdhExpiry() {
        return sipdhExpiry;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public List<String> getSpecialties() {
        return specialties;
    }
}
