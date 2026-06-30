package com.tailtopia.admin.vetqual.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import com.tailtopia.admin.vetqual.dto.QualificationForm;
import com.tailtopia.admin.vetqual.repository.VetQualificationRepository;
import com.tailtopia.shared.error.AppException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：资质录入/审核/续期状态机 + 审计（Story 2.7，mock repo/audit）。 */
class VetQualificationReviewTest {

    private VetQualificationRepository repo;
    private AdminAuditService audit;
    private VetQualificationService service;

    @BeforeEach
    void setUp() {
        repo = mock(VetQualificationRepository.class);
        audit = mock(AdminAuditService.class);
        service = new VetQualificationService(repo, audit);
        when(repo.save(any(VetQualification.class))).thenAnswer(i -> i.getArgument(0));
    }

    private VetQualification rowWithStatus(QualificationStatus st) {
        VetQualification q = VetQualification.pendingFor(8L);
        ReflectionTestUtils.setField(q, "status", st);
        return q;
    }

    private QualificationForm fullForm() {
        QualificationForm f = new QualificationForm();
        f.setKtpNo("KTP1");
        f.setKtpPhotoKey("private/ktp/1");
        f.setSipdhNo("SIPDH1");
        f.setSipdhIssuer("PDHI Jakarta");
        f.setSipdhExpiry(LocalDate.of(2027, 1, 1));
        f.setSipdhPhotoKey("private/sipdh/1");
        f.setDegreePhotoKey("private/degree/1");
        return f;
    }

    // ===== 直录 =====

    @Test
    void recordByOpsCertifiesAndAudits() {
        when(repo.findByVetAccountId(8L)).thenReturn(Optional.of(rowWithStatus(QualificationStatus.PENDING_COMPLETION)));
        service.recordByOps(8L, fullForm(), 3L);
        verify(audit).record(eq(3L), eq(AuditActions.VET_QUALIFICATION_RECORDED), eq("VET_QUALIFICATION"),
                eq("8"), any());
    }

    @Test
    void recordByOpsRejectsIncompleteInput() {
        QualificationForm partial = new QualificationForm();
        partial.setKtpNo("only");
        assertThatThrownBy(() -> service.recordByOps(8L, partial, 3L)).isInstanceOf(AppException.class);
    }

    // ===== 审核通过 / 驳回 =====

    @Test
    void approveOnlyFromUnderReview() {
        when(repo.findByVetAccountId(8L)).thenReturn(Optional.of(rowWithStatus(QualificationStatus.UNDER_REVIEW)));
        service.approve(8L, 3L);
        verify(audit).record(eq(3L), eq(AuditActions.VET_QUALIFICATION_APPROVED), any(), eq("8"), any());
    }

    @Test
    void approveFromNonUnderReviewThrows() {
        when(repo.findByVetAccountId(8L)).thenReturn(Optional.of(rowWithStatus(QualificationStatus.CERTIFIED)));
        assertThatThrownBy(() -> service.approve(8L, 3L)).isInstanceOf(AppException.class);
    }

    @Test
    void rejectRequiresReasonAndUnderReview() {
        when(repo.findByVetAccountId(8L)).thenReturn(Optional.of(rowWithStatus(QualificationStatus.UNDER_REVIEW)));
        assertThatThrownBy(() -> service.reject(8L, "  ", 3L)).isInstanceOf(AppException.class);

        service.reject(8L, "证件模糊", 3L);
        verify(audit).record(eq(3L), eq(AuditActions.VET_QUALIFICATION_REJECTED), any(), eq("8"), any());
    }

    // ===== 续期 =====

    @Test
    void renewFromCertifiedKeepsCertified() {
        VetQualification q = rowWithStatus(QualificationStatus.EXPIRED);
        when(repo.findByVetAccountId(8L)).thenReturn(Optional.of(q));
        QualificationForm f = new QualificationForm();
        f.setSipdhExpiry(LocalDate.of(2028, 1, 1));
        f.setSipdhPhotoKey("private/sipdh/new");
        service.renew(8L, f, 3L);
        assertThat(q.getStatus()).isEqualTo(QualificationStatus.CERTIFIED);
        verify(audit).record(eq(3L), eq(AuditActions.VET_QUALIFICATION_RENEWED), any(), eq("8"), any());
    }

    @Test
    void renewFromPendingThrows() {
        when(repo.findByVetAccountId(8L)).thenReturn(Optional.of(rowWithStatus(QualificationStatus.PENDING_COMPLETION)));
        QualificationForm f = new QualificationForm();
        f.setSipdhExpiry(LocalDate.of(2028, 1, 1));
        f.setSipdhPhotoKey("private/sipdh/new");
        assertThatThrownBy(() -> service.renew(8L, f, 3L)).isInstanceOf(AppException.class);
    }
}
