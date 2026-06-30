package com.tailtopia.admin.vetqual.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import com.tailtopia.admin.vetqual.repository.VetQualificationRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：SIPDH 到期扫描（Story 2.8）——过期→EXPIRED、≤30 天→EXPIRING_SOON、未到期保持、边界、幂等。 */
class VetQualificationScanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 29);

    private VetQualificationRepository repo;
    private VetQualificationService service;

    @BeforeEach
    void setUp() {
        repo = mock(VetQualificationRepository.class);
        service = new VetQualificationService(repo, mock(AdminAuditService.class));
        when(repo.save(any(VetQualification.class))).thenAnswer(i -> i.getArgument(0));
    }

    private VetQualification row(QualificationStatus st, LocalDate expiry) {
        VetQualification q = VetQualification.pendingFor(1L);
        ReflectionTestUtils.setField(q, "status", st);
        ReflectionTestUtils.setField(q, "sipdhExpiry", expiry);
        return q;
    }

    @Test
    void expiredAndWarnedAndUntouchedClassification() {
        VetQualification past = row(QualificationStatus.CERTIFIED, TODAY.minusDays(1));      // 过期
        VetQualification soon = row(QualificationStatus.CERTIFIED, TODAY.plusDays(10));       // 即将到期
        VetQualification edge30 = row(QualificationStatus.CERTIFIED, TODAY.plusDays(30));     // 恰好 30 天 → 预警
        VetQualification far = row(QualificationStatus.CERTIFIED, TODAY.plusDays(31));        // 未到期 → 保持
        VetQualification alreadySoon = row(QualificationStatus.EXPIRING_SOON, TODAY.plusDays(5)); // 已预警 → 不重复
        when(repo.findByStatusInAndSipdhExpiryNotNull(any()))
                .thenReturn(List.of(past, soon, edge30, far, alreadySoon));

        VetQualificationService.ScanResult r = service.scanExpiry(TODAY);

        assertThat(past.getStatus()).isEqualTo(QualificationStatus.EXPIRED);
        assertThat(soon.getStatus()).isEqualTo(QualificationStatus.EXPIRING_SOON);
        assertThat(edge30.getStatus()).isEqualTo(QualificationStatus.EXPIRING_SOON);
        assertThat(far.getStatus()).isEqualTo(QualificationStatus.CERTIFIED);
        assertThat(alreadySoon.getStatus()).isEqualTo(QualificationStatus.EXPIRING_SOON);
        assertThat(r.expired()).isEqualTo(1);
        assertThat(r.warned()).isEqualTo(2);
    }

    @Test
    void expiringSoonPastExpiryBecomesExpired() {
        VetQualification soonButPast = row(QualificationStatus.EXPIRING_SOON, TODAY.minusDays(1));
        when(repo.findByStatusInAndSipdhExpiryNotNull(any())).thenReturn(List.of(soonButPast));
        service.scanExpiry(TODAY);
        assertThat(soonButPast.getStatus()).isEqualTo(QualificationStatus.EXPIRED);
    }

    @Test
    void todayExactNotYetExpiredBecomesWarning() {
        // 有效期最后一天（= today）不阻断，仅预警（expiry < today 才过期）。
        VetQualification lastDay = row(QualificationStatus.CERTIFIED, TODAY);
        when(repo.findByStatusInAndSipdhExpiryNotNull(any())).thenReturn(List.of(lastDay));
        service.scanExpiry(TODAY);
        assertThat(lastDay.getStatus()).isEqualTo(QualificationStatus.EXPIRING_SOON);
    }

    @Test
    void scannerSwallowsExceptions() {
        VetQualificationService failing = mock(VetQualificationService.class);
        when(failing.scanExpiry(any())).thenThrow(new RuntimeException("boom"));
        SipdhExpiryScanner scanner = new SipdhExpiryScanner(failing);
        assertThatCode(scanner::scan).doesNotThrowAnyException();
    }
}
