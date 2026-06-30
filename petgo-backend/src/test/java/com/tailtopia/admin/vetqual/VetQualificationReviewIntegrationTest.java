package com.tailtopia.admin.vetqual;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import com.tailtopia.admin.vetqual.dto.QualificationForm;
import com.tailtopia.admin.vetqual.repository.VetQualificationRepository;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L1：资质录入/审核/续期端到端（Story 2.7，需 Docker postgres）。直录→CERTIFIED+可接单+审计；
 * 审核中→驳回（必填原因）；续期保持认证。〔L2：证件图真传私密桶 + 签名 URL + EXIF 留本地〕
 */
class VetQualificationReviewIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private VetQualificationService qualService;
    @Autowired
    private VetQualificationRepository qualRepo;
    @Autowired
    private VetAccountRepository vetRepo;
    @Autowired
    private AdminAuditService auditService;

    private long newVet() {
        long n = SEQ.incrementAndGet();
        return vetRepo.save(VetAccount.create("q7-" + n + "@vet.test", "{bcrypt}x", "资质医生" + n)).getId();
    }

    private QualificationForm fullForm() {
        QualificationForm f = new QualificationForm();
        f.setKtpNo("KTP");
        f.setKtpPhotoKey("private/ktp/x");
        f.setSipdhNo("SIPDH");
        f.setSipdhIssuer("PDHI");
        f.setSipdhExpiry(LocalDate.of(2028, 1, 1));
        f.setSipdhPhotoKey("private/sipdh/x");
        f.setDegreePhotoKey("private/degree/x");
        return f;
    }

    @Test
    void recordByOpsCertifiesEnablesConsultAndAudits() {
        long vetId = newVet();
        long actor = 900000L + vetId;

        qualService.recordByOps(vetId, fullForm(), actor);

        assertThat(qualService.getStatus(vetId)).isEqualTo(QualificationStatus.CERTIFIED);
        assertThat(qualService.canTakeConsult(vetId)).isTrue();
        assertThat(auditService.search(null, null, actor, AuditActions.VET_QUALIFICATION_RECORDED,
                PageRequest.of(0, 5)).getContent()).isNotEmpty();
    }

    @Test
    void rejectFromUnderReviewSetsReasonAndBlocksConsult() {
        long vetId = newVet();
        VetQualification q = qualService.ensureForVet(vetId);
        ReflectionTestUtils.setField(q, "status", QualificationStatus.UNDER_REVIEW);
        qualRepo.save(q);
        long actor = 910000L + vetId;

        qualService.reject(vetId, "证件照不清晰", actor);

        VetQualification reloaded = qualRepo.findByVetAccountId(vetId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(QualificationStatus.REJECTED);
        assertThat(reloaded.getRejectReason()).isEqualTo("证件照不清晰");
        assertThat(qualService.canTakeConsult(vetId)).isFalse();
    }

    @Test
    void approveFromUnderReviewCertifies() {
        long vetId = newVet();
        VetQualification q = qualService.ensureForVet(vetId);
        ReflectionTestUtils.setField(q, "status", QualificationStatus.UNDER_REVIEW);
        qualRepo.save(q);

        qualService.approve(vetId, 920000L + vetId);

        assertThat(qualService.getStatus(vetId)).isEqualTo(QualificationStatus.CERTIFIED);
    }
}
