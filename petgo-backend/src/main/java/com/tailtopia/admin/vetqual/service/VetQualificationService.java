package com.tailtopia.admin.vetqual.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import com.tailtopia.admin.vetqual.dto.QualificationForm;
import com.tailtopia.admin.vetqual.repository.VetQualificationRepository;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医资质服务（Story 2.1，AB-2H）。资质 1:1 行的创建/读取 + 接单门控判定。跨模块只经本 service（禁跨 repo）。
 *
 * <p>存量兽医（Epic 5 已建、无资质行）兜底：{@link #getStatus}/{@link #canTakeConsult} 对无行视同
 * {@code PENDING_COMPLETION}（不可接单），避免 NPE；不做数据迁移补行（冻结迁移，由 2.7 录入时惰性 ensure）。
 */
@Service
public class VetQualificationService {

    private final VetQualificationRepository repo;
    private final AdminAuditService auditService;

    public VetQualificationService(VetQualificationRepository repo, AdminAuditService auditService) {
        this.repo = repo;
        this.auditService = auditService;
    }

    /** 幂等创建待完善资质行（建号后调，Story 2.3）。已存在则原样返回。 */
    @Transactional
    public VetQualification ensureForVet(long vetAccountId) {
        return repo.findByVetAccountId(vetAccountId)
                .orElseGet(() -> repo.save(VetQualification.pendingFor(vetAccountId)));
    }

    /** 读资质行（可能无行）。 */
    @Transactional(readOnly = true)
    public Optional<VetQualification> findForVet(long vetAccountId) {
        return repo.findByVetAccountId(vetAccountId);
    }

    /** 资质状态；无行视同待完善。 */
    @Transactional(readOnly = true)
    public QualificationStatus getStatus(long vetAccountId) {
        return repo.findByVetAccountId(vetAccountId)
                .map(VetQualification::getStatus)
                .orElse(QualificationStatus.PENDING_COMPLETION);
    }

    /** 接单门控：仅 CERTIFIED / EXPIRING_SOON 可接单；无行（待完善）不可。 */
    @Transactional(readOnly = true)
    public boolean canTakeConsult(long vetAccountId) {
        return getStatus(vetAccountId).canTakeConsult();
    }

    // ===== Story 2.7：录入 / 审核 / 续期（均写审计，summary 绝不含证件号/key/签名 URL）=====

    /** 运营直录 → 直接已认证（AC1）。填全量字段，状态置 CERTIFIED。 */
    @Transactional
    public void recordByOps(long vetId, QualificationForm form, long actorAccountId) {
        requireFullInput(form);
        VetQualification q = ensureForVet(vetId);
        applyForm(q, form);
        q.markCertified();
        repo.save(q);
        auditService.record(actorAccountId, AuditActions.VET_QUALIFICATION_RECORDED,
                "VET_QUALIFICATION", String.valueOf(vetId), "运营直录兽医资质并认证");
    }

    /** 审核通过（AC2）：UNDER_REVIEW → CERTIFIED。 */
    @Transactional
    public void approve(long vetId, long actorAccountId) {
        VetQualification q = requireRow(vetId);
        q.approve();
        repo.save(q);
        auditService.record(actorAccountId, AuditActions.VET_QUALIFICATION_APPROVED,
                "VET_QUALIFICATION", String.valueOf(vetId), "审核通过兽医资质");
    }

    /** 驳回（AC2）：UNDER_REVIEW → REJECTED，必填原因。 */
    @Transactional
    public void reject(long vetId, String reason, long actorAccountId) {
        VetQualification q = requireRow(vetId);
        q.reject(reason);
        repo.save(q);
        // summary 只记原因长度，不落原文（原因可能含敏感信息）。
        auditService.record(actorAccountId, AuditActions.VET_QUALIFICATION_REJECTED,
                "VET_QUALIFICATION", String.valueOf(vetId),
                "驳回兽医资质（原因 " + reason.trim().length() + " 字）");
    }

    /** 续期（AC3）：CERTIFIED/EXPIRING_SOON/EXPIRED → CERTIFIED + 更新有效期/证件图。 */
    @Transactional
    public void renew(long vetId, QualificationForm form, long actorAccountId) {
        if (form.getSipdhExpiry() == null) {
            throw AppException.validation("续期须填写新的 SIPDH 有效期");
        }
        if (form.getSipdhPhotoKey() == null || form.getSipdhPhotoKey().isBlank()) {
            throw AppException.validation("续期须上传新的 SIPDH 证件图");
        }
        VetQualification q = requireRow(vetId);
        q.setSipdhExpiry(form.getSipdhExpiry());
        q.setSipdhPhotoKey(form.getSipdhPhotoKey());
        q.renew();
        repo.save(q);
        auditService.record(actorAccountId, AuditActions.VET_QUALIFICATION_RENEWED,
                "VET_QUALIFICATION", String.valueOf(vetId), "兽医资质续期");
    }

    // ===== Story 2.8：SIPDH 到期扫描（系统行为，不写审计；状态机幂等去重，禁中间件）=====

    /**
     * 扫描 SIPDH 有效期并单向收紧状态：{@code expiry < today} → EXPIRED（停接单）；
     * {@code today <= expiry <= today+30} 且当前 CERTIFIED → EXPIRING_SOON（仍可接单）。
     * 仅查 CERTIFIED/EXPIRING_SOON（幂等：EXPIRED 不再处理）。续期回切由 2.7 renew 负责，扫描器不回切。
     *
     * @return [expired 数, warned 数]
     */
    @Transactional
    public ScanResult scanExpiry(java.time.LocalDate today) {
        java.time.LocalDate warnThreshold = today.plusDays(30);
        int expired = 0;
        int warned = 0;
        for (VetQualification q : repo.findByStatusInAndSipdhExpiryNotNull(
                java.util.List.of(QualificationStatus.CERTIFIED, QualificationStatus.EXPIRING_SOON))) {
            java.time.LocalDate exp = q.getSipdhExpiry();
            if (exp.isBefore(today)) {
                q.markExpired();
                repo.save(q);
                expired++;
            } else if (q.getStatus() == QualificationStatus.CERTIFIED && !exp.isAfter(warnThreshold)) {
                q.markExpiringSoon();
                repo.save(q);
                warned++;
            }
        }
        return new ScanResult(expired, warned);
    }

    /** 资质到期统计（Story 2.8 预警徽标）。 */
    @Transactional(readOnly = true)
    public ExpiryStats expiryStats() {
        return new ExpiryStats(
                repo.countByStatus(QualificationStatus.EXPIRING_SOON),
                repo.countByStatus(QualificationStatus.EXPIRED));
    }

    /** 扫描结果。 */
    public record ScanResult(int expired, int warned) {
    }

    /** 到期统计：即将到期 / 已过期数量。 */
    public record ExpiryStats(long expiringSoon, long expired) {
    }

    private VetQualification requireRow(long vetId) {
        return repo.findByVetAccountId(vetId)
                .orElseThrow(() -> AppException.notFound("该兽医无资质记录"));
    }

    private void requireFullInput(QualificationForm f) {
        if (blank(f.getKtpNo()) || blank(f.getKtpPhotoKey()) || blank(f.getSipdhNo())
                || blank(f.getSipdhIssuer()) || f.getSipdhExpiry() == null
                || blank(f.getSipdhPhotoKey()) || blank(f.getDegreePhotoKey())) {
            throw AppException.validation("请完整填写 KTP/SIPDH 编号·机构·有效期及证件图");
        }
    }

    private void applyForm(VetQualification q, QualificationForm f) {
        q.setKtpNo(f.getKtpNo());
        q.setKtpPhotoKey(f.getKtpPhotoKey());
        q.setSipdhNo(f.getSipdhNo());
        q.setSipdhIssuer(f.getSipdhIssuer());
        q.setSipdhExpiry(f.getSipdhExpiry());
        q.setSipdhPhotoKey(f.getSipdhPhotoKey());
        q.setStrvNo(f.getStrvNo());
        q.setStrvIssuer(f.getStrvIssuer());
        q.setStrvExpiry(f.getStrvExpiry());
        q.setStrvPhotoKey(f.getStrvPhotoKey());
        q.setDegreePhotoKey(f.getDegreePhotoKey());
        q.setProfilePhotoKey(f.getProfilePhotoKey());
        q.setPdhiPhotoKey(f.getPdhiPhotoKey());
        q.setSpecialties(f.specialties());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
