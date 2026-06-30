package com.tailtopia.admin.vet;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.service.AdminVetService;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.domain.VetStatus;
import com.tailtopia.vet.repository.VetAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * L1：封禁/解封（Story 2.5，需 Docker postgres+redis）。封禁 → 兽医 BANNED + 进行中会话 INTERRUPTED
 * + 写 VET_BANNED 审计；解封 → ACTIVE + 写 VET_UNBANNED 审计、不恢复会话。
 */
class AdminVetBanIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminVetService adminVetService;
    @Autowired
    private VetAccountRepository vetRepo;
    @Autowired
    private ConsultSessionRepository sessions;
    @Autowired
    private AdminAuditService auditService;

    @Test
    void banInterruptsInProgressSessionAndAudits() {
        long seq = SEQ.incrementAndGet();
        VetAccount vet = vetRepo.save(VetAccount.create("ban-" + seq + "@vet.test", "{bcrypt}x", "待封"));
        ConsultSession s = ConsultSession.startWaiting(8000L + seq, ConsultSource.DIRECT);
        s.markInProgress(vet.getId());
        s.attachImConversation("conv-ban-" + seq);
        sessions.save(s);
        long actor = 800000L + seq;

        adminVetService.setBanned(vet.getId(), true, actor);

        assertThat(vetRepo.findById(vet.getId()).orElseThrow().getStatus()).isEqualTo(VetStatus.BANNED);
        assertThat(sessions.findById(s.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.INTERRUPTED);
        assertThat(auditService.search(null, null, actor, AuditActions.VET_BANNED, PageRequest.of(0, 5))
                .getContent()).isNotEmpty();
    }

    @Test
    void unbanRestoresActiveAndAudits() {
        long seq = SEQ.incrementAndGet();
        VetAccount vet = vetRepo.save(VetAccount.create("unban-" + seq + "@vet.test", "{bcrypt}x", "待解"));
        long actor = 810000L + seq;
        adminVetService.setBanned(vet.getId(), true, actor);

        adminVetService.setBanned(vet.getId(), false, actor);

        assertThat(vetRepo.findById(vet.getId()).orElseThrow().getStatus()).isEqualTo(VetStatus.ACTIVE);
        assertThat(auditService.search(null, null, actor, AuditActions.VET_UNBANNED, PageRequest.of(0, 5))
                .getContent()).isNotEmpty();
    }
}
