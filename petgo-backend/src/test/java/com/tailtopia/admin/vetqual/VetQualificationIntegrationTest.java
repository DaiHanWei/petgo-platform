package com.tailtopia.admin.vetqual;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.domain.VetQualification;
import com.tailtopia.admin.vetqual.repository.VetQualificationRepository;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L1：兽医资质（Story 2.1，需 Docker postgres）。V33 迁移 validate 绿、ensureForVet 幂等建待完善行、
 * 接单门控按状态判定（PENDING 不可、CERTIFIED 可）。
 */
class VetQualificationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private VetQualificationService service;
    @Autowired
    private VetQualificationRepository qualRepo;
    @Autowired
    private VetAccountRepository vetRepo;

    private long newVet() {
        long n = SEQ.incrementAndGet();
        VetAccount v = vetRepo.save(VetAccount.create("vet-q-" + n, "{bcrypt}x", "资质测试" + n));
        return v.getId();
    }

    @Test
    void ensureCreatesPendingRowAndBlocksConsult() {
        long vetId = newVet();

        VetQualification q = service.ensureForVet(vetId);
        assertThat(q.getStatus()).isEqualTo(QualificationStatus.PENDING_COMPLETION);
        assertThat(service.canTakeConsult(vetId)).isFalse();

        // 幂等：再 ensure 不新建（仍唯一 1:1）。
        service.ensureForVet(vetId);
        assertThat(qualRepo.findByVetAccountId(vetId)).isPresent();
    }

    @Test
    void certifiedQualificationAllowsConsult() {
        long vetId = newVet();
        VetQualification q = service.ensureForVet(vetId);
        ReflectionTestUtils.setField(q, "status", QualificationStatus.CERTIFIED);
        qualRepo.save(q);

        assertThat(service.canTakeConsult(vetId)).isTrue();
    }

    @Test
    void unknownVetTreatedAsPending() {
        assertThat(service.getStatus(999_999_999L)).isEqualTo(QualificationStatus.PENDING_COMPLETION);
        assertThat(service.canTakeConsult(999_999_999L)).isFalse();
    }
}
