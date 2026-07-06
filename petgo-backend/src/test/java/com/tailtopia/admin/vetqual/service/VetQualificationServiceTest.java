package com.tailtopia.admin.vetqual.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import com.tailtopia.admin.vetqual.repository.VetQualificationRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：资质服务（AC2/AC3/AC4，mock repo）——无行=待完善、ensure 幂等、canTakeConsult 6 态判定。 */
class VetQualificationServiceTest {

    private VetQualificationRepository repo;
    private VetQualificationService service;

    @BeforeEach
    void setUp() {
        repo = mock(VetQualificationRepository.class);
        service = new VetQualificationService(repo,
                mock(com.tailtopia.admin.audit.service.AdminAuditService.class));
        when(repo.save(any(VetQualification.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private VetQualification withStatus(QualificationStatus st) {
        VetQualification q = VetQualification.pendingFor(5L);
        ReflectionTestUtils.setField(q, "status", st);
        return q;
    }

    @Test
    void noRowMeansPendingCompletionAndCannotTakeConsult() {
        when(repo.findByVetAccountId(5L)).thenReturn(Optional.empty());
        assertThat(service.getStatus(5L)).isEqualTo(QualificationStatus.PENDING_COMPLETION);
        assertThat(service.canTakeConsult(5L)).isFalse();
    }

    @Test
    void ensureForVetIsIdempotent() {
        // 已存在 → 不再 save。
        when(repo.findByVetAccountId(5L)).thenReturn(Optional.of(withStatus(QualificationStatus.CERTIFIED)));
        service.ensureForVet(5L);
        verify(repo, never()).save(any());

        // 不存在 → 创建一行 PENDING。
        when(repo.findByVetAccountId(6L)).thenReturn(Optional.empty());
        VetQualification created = service.ensureForVet(6L);
        assertThat(created.getStatus()).isEqualTo(QualificationStatus.PENDING_COMPLETION);
        assertThat(created.getVetAccountId()).isEqualTo(6L);
        verify(repo).save(any(VetQualification.class));
    }

    @Test
    void canTakeConsultOnlyForCertifiedAndExpiringSoon() {
        record Case(QualificationStatus status, boolean expected) { }
        Case[] cases = {
            new Case(QualificationStatus.PENDING_COMPLETION, false),
            new Case(QualificationStatus.UNDER_REVIEW, false),
            new Case(QualificationStatus.CERTIFIED, true),
            new Case(QualificationStatus.REJECTED, false),
            new Case(QualificationStatus.EXPIRING_SOON, true),
            new Case(QualificationStatus.EXPIRED, false),
        };
        for (Case c : cases) {
            when(repo.findByVetAccountId(5L)).thenReturn(Optional.of(withStatus(c.status())));
            assertThat(service.canTakeConsult(5L)).as(c.status().name()).isEqualTo(c.expected());
        }
    }

    @Test
    void enumCanTakeConsultMatchesSpec() {
        assertThat(QualificationStatus.CERTIFIED.canTakeConsult()).isTrue();
        assertThat(QualificationStatus.EXPIRING_SOON.canTakeConsult()).isTrue();
        assertThat(QualificationStatus.PENDING_COMPLETION.canTakeConsult()).isFalse();
        assertThat(QualificationStatus.EXPIRED.canTakeConsult()).isFalse();
    }

    /** Bug 166：STRV 为可选留档字段——直录时若填了则持久化，且不在 requireFullInput 必填集内。 */
    @Test
    void recordPersistsOptionalStrvFields() {
        when(repo.findByVetAccountId(5L)).thenReturn(Optional.of(VetQualification.pendingFor(5L)));
        var form = new com.tailtopia.admin.vetqual.dto.QualificationForm();
        // 必填（不含 STRV）
        form.setKtpNo("KTP1");
        form.setKtpPhotoKey("k/ktp");
        form.setSipdhNo("SIPDH1");
        form.setSipdhIssuer("PDHI Jakarta");
        form.setSipdhExpiry(java.time.LocalDate.of(2030, 1, 1));
        form.setSipdhPhotoKey("k/sipdh");
        form.setDegreePhotoKey("k/degree");
        // 可选 STRV
        form.setStrvNo("STRV-9");
        form.setStrvIssuer("KKH");
        form.setStrvExpiry(java.time.LocalDate.of(2031, 6, 30));
        form.setStrvPhotoKey("k/strv");

        var captor = org.mockito.ArgumentCaptor.forClass(VetQualification.class);
        service.recordByOps(5L, form, 1L);
        verify(repo).save(captor.capture());
        VetQualification saved = captor.getValue();
        assertThat(saved.getStrvNo()).isEqualTo("STRV-9");
        assertThat(saved.getStrvIssuer()).isEqualTo("KKH");
        assertThat(saved.getStrvExpiry()).isEqualTo(java.time.LocalDate.of(2031, 6, 30));
        assertThat(saved.getStrvPhotoKey()).isEqualTo("k/strv");
        assertThat(saved.getStatus()).isEqualTo(QualificationStatus.CERTIFIED);
    }

    /** STRV 全空也能直录成功（可选，不进 requireFullInput）。 */
    @Test
    void recordSucceedsWithoutStrv() {
        when(repo.findByVetAccountId(5L)).thenReturn(Optional.of(VetQualification.pendingFor(5L)));
        var form = new com.tailtopia.admin.vetqual.dto.QualificationForm();
        form.setKtpNo("KTP1");
        form.setKtpPhotoKey("k/ktp");
        form.setSipdhNo("SIPDH1");
        form.setSipdhIssuer("PDHI Jakarta");
        form.setSipdhExpiry(java.time.LocalDate.of(2030, 1, 1));
        form.setSipdhPhotoKey("k/sipdh");
        form.setDegreePhotoKey("k/degree");

        var captor = org.mockito.ArgumentCaptor.forClass(VetQualification.class);
        service.recordByOps(5L, form, 1L);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStrvNo()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo(QualificationStatus.CERTIFIED);
    }
}
