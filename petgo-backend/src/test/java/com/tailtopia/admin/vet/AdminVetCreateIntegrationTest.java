package com.tailtopia.admin.vet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.service.AdminVetService;
import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * L1：建号端到端（Story 2.3，需 Docker postgres）。落库含 contact_phone（V34 validate 绿）、
 * 资质 PENDING_COMPLETION（不可接单）、写 VET_CREATED 审计（summary 不含密码/手机号）、邮箱唯一。
 */
class AdminVetCreateIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminVetService adminVetService;
    @Autowired
    private VetAccountRepository vetRepo;
    @Autowired
    private VetQualificationService vetQual;
    @Autowired
    private AdminAuditService auditService;

    @Test
    void createPersistsPhonePendingQualAndAudits() {
        long seq = SEQ.incrementAndGet();
        String email = "create-" + seq + "@vet.test";
        String phone = "+62-811-" + seq;
        long actor = 500000L + seq;

        long vetId = adminVetService.create("建号医生", email, "Secret#" + seq, phone, actor);

        VetAccount v = vetRepo.findById(vetId).orElseThrow();
        assertThat(v.getContactPhone()).isEqualTo(phone);
        // 资质待完善 → 不可接单。
        assertThat(vetQual.getStatus(vetId)).isEqualTo(QualificationStatus.PENDING_COMPLETION);
        assertThat(vetQual.canTakeConsult(vetId)).isFalse();

        // 审计 VET_CREATED：含邮箱，不含密码/手机号。
        List<AdminAuditLog> audits = auditService.search(null, null, actor,
                AuditActions.VET_CREATED, PageRequest.of(0, 10)).getContent();
        assertThat(audits).isNotEmpty();
        String summary = audits.get(0).getSummary();
        assertThat(summary).contains(email).doesNotContain("Secret#" + seq).doesNotContain(phone);
    }

    @Test
    void duplicateLoginEmailRejected() {
        long seq = SEQ.incrementAndGet();
        String email = "dup-" + seq + "@vet.test";
        adminVetService.create("第一个", email, "Secret#" + seq, "+62-1", 600000L + seq);
        assertThatThrownBy(() ->
                adminVetService.create("第二个", email, "Secret#" + seq, "+62-2", 600000L + seq))
                .isInstanceOf(AppException.class);
    }
}
