package com.tailtopia.admin.vetqual;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import com.tailtopia.admin.vetqual.repository.VetQualificationRepository;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L1：SIPDH 到期扫描（Story 2.8，需 Docker postgres）。过期→EXPIRED(停接单)、≤30 天→EXPIRING_SOON(仍可接单)。
 */
class SipdhExpiryScanIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private VetQualificationService qualService;
    @Autowired
    private VetQualificationRepository qualRepo;
    @Autowired
    private VetAccountRepository vetRepo;

    private long certifiedVetWithExpiry(LocalDate expiry) {
        long n = SEQ.incrementAndGet();
        long vetId = vetRepo.save(VetAccount.create("scan-" + n + "@vet.test", "{bcrypt}x", "扫描" + n)).getId();
        VetQualification q = qualService.ensureForVet(vetId);
        ReflectionTestUtils.setField(q, "status", QualificationStatus.CERTIFIED);
        ReflectionTestUtils.setField(q, "sipdhExpiry", expiry);
        qualRepo.save(q);
        return vetId;
    }

    @Test
    void scanExpiresPastAndWarnsSoon() {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        long expiredVet = certifiedVetWithExpiry(today.minusDays(1));
        long soonVet = certifiedVetWithExpiry(today.plusDays(10));
        long farVet = certifiedVetWithExpiry(today.plusDays(120));

        qualService.scanExpiry(today);

        assertThat(qualService.getStatus(expiredVet)).isEqualTo(QualificationStatus.EXPIRED);
        assertThat(qualService.canTakeConsult(expiredVet)).isFalse();

        assertThat(qualService.getStatus(soonVet)).isEqualTo(QualificationStatus.EXPIRING_SOON);
        assertThat(qualService.canTakeConsult(soonVet)).isTrue(); // 即将到期仍可接单

        assertThat(qualService.getStatus(farVet)).isEqualTo(QualificationStatus.CERTIFIED);
    }
}
