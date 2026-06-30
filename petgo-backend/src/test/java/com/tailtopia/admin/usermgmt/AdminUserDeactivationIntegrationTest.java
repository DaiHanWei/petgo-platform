package com.tailtopia.admin.usermgmt;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.usermgmt.service.AdminUserService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * L1：停用/激活（Story 3.2，需 Docker postgres+redis）。V36 validate；停用→DEACTIVATED + 进行中会话 INTERRUPTED
 * + USER_DEACTIVATED 审计；重新激活→ACTIVE + USER_REACTIVATED 审计。
 */
class AdminUserDeactivationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private UserRepository users;
    @Autowired
    private ConsultSessionRepository sessions;
    @Autowired
    private AdminAuditService auditService;

    @Test
    void deactivateSetsStatusInterruptsSessionAndAudits() {
        User u = newUser();
        ConsultSession s = ConsultSession.startWaiting(u.getId(), ConsultSource.DIRECT);
        s.markInProgress(5000L + SEQ.incrementAndGet());
        s.attachImConversation("conv-deac-" + s.getVetId());
        sessions.save(s);
        long actor = 300000L + SEQ.incrementAndGet();

        adminUserService.deactivate(u.getId(), "违规内容", actor);

        assertThat(users.findById(u.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(sessions.findById(s.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.INTERRUPTED);
        assertThat(auditService.search(null, null, actor, AuditActions.USER_DEACTIVATED, PageRequest.of(0, 5))
                .getContent()).isNotEmpty();
    }

    @Test
    void reactivateRestoresActiveAndAudits() {
        User u = newUser();
        long actor = 310000L + SEQ.incrementAndGet();
        adminUserService.deactivate(u.getId(), "暂时停用", actor);

        adminUserService.reactivate(u.getId(), actor);

        assertThat(users.findById(u.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(auditService.search(null, null, actor, AuditActions.USER_REACTIVATED, PageRequest.of(0, 5))
                .getContent()).isNotEmpty();
    }
}
