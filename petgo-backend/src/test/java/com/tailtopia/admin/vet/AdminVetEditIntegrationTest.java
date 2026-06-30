package com.tailtopia.admin.vet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.service.AdminVetService;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.repository.VetAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：编辑兽医资料（Story 2.4，需 Docker postgres）。落库改名/邮箱/手机号；**不中断进行中会话**（AC2）；
 * 邮箱唯一排除自身；改成他人邮箱被拒。
 */
class AdminVetEditIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminVetService adminVetService;
    @Autowired
    private VetAccountRepository vetRepo;
    @Autowired
    private ConsultSessionRepository sessions;

    private VetAccount newVet(String email) {
        return vetRepo.save(VetAccount.create(email, "{bcrypt}x", "原名"));
    }

    @Test
    void editPersistsAndDoesNotTouchInProgressSession() {
        long seq = SEQ.incrementAndGet();
        VetAccount vet = newVet("edit-" + seq + "@vet.test");
        // 该兽医有一个进行中会话。
        ConsultSession s = ConsultSession.startWaiting(7000L + seq, com.tailtopia.consult.domain.ConsultSource.DIRECT);
        s.markInProgress(vet.getId());
        s.attachImConversation("conv-edit-" + seq);
        sessions.save(s);

        adminVetService.updateProfile(vet.getId(), "新名字", "edited-" + seq + "@vet.test",
                "+62-700-" + seq, 700000L + seq);

        VetAccount reloaded = vetRepo.findById(vet.getId()).orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("新名字");
        assertThat(reloaded.getUsername()).isEqualTo("edited-" + seq + "@vet.test");
        assertThat(reloaded.getContactPhone()).isEqualTo("+62-700-" + seq);
        // 会话状态不受编辑影响（AC2）。
        assertThat(sessions.findById(s.getId()).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.IN_PROGRESS);
    }

    @Test
    void editToOthersEmailRejectedButSameEmailOk() {
        long seq = SEQ.incrementAndGet();
        VetAccount a = newVet("a-" + seq + "@vet.test");
        VetAccount b = newVet("b-" + seq + "@vet.test");

        // 改成已被 b 占用的邮箱 → 拒绝。
        assertThatThrownBy(() -> adminVetService.updateProfile(a.getId(), "原名",
                "b-" + seq + "@vet.test", null, 700000L + seq)).isInstanceOf(AppException.class);

        // 保持自身邮箱不变 + 改其它字段 → 允许（唯一校验排除自身）。
        adminVetService.updateProfile(a.getId(), "改了名", "a-" + seq + "@vet.test", "+62-1", 700000L + seq);
        assertThat(vetRepo.findById(a.getId()).orElseThrow().getDisplayName()).isEqualTo("改了名");
    }
}
