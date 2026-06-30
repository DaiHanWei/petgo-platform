package com.tailtopia.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.audit.service.AuditChainVerifier;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * L1：审计模块集成（需 Docker postgres）。验证 V32 迁移 validate 绿（上下文能起）、真实哈希链续接、
 * append-only 触发器硬拒 UPDATE/DELETE、并发写链不断裂、紧急账密登录成功写审计（AC2/AC3/AC4/AC7）。
 */
class AdminAuditIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminAuditService auditService;
    @Autowired
    private AuditChainVerifier verifier;
    @Autowired
    private AdminAuditLogRepository auditLogs;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void recordsFormImmutableChainAndVerifies() {
        AdminAuditLog a = auditService.record(1L, AuditActions.ACCOUNT_CREATED, "ADMIN_ACCOUNT", "x1", "建号");
        AdminAuditLog b = auditService.record(1L, AuditActions.VET_BANNED, "VET", "v_tok", "封禁");

        // 链接续：后一行 prevHash = 前一行 rowHash。
        assertThat(b.getPrevHash()).isEqualTo(a.getRowHash());
        // 全链复算校验通过（含此前其他测试写入的行）。
        assertThat(verifier.verifyAll().intact()).isTrue();
    }

    @Test
    void appendOnlyTriggerBlocksUpdateAndDelete() {
        AdminAuditLog row = auditService.record(1L, AuditActions.USER_DEACTIVATED, "USER", "u1", "停用");
        Long id = row.getId();

        assertThatThrownBy(() ->
                jdbc.update("UPDATE admin_audit_logs SET summary = 'tampered' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM admin_audit_logs WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);

        // 行仍在且未被改。
        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_logs WHERE id = ? AND summary = '停用'",
                Integer.class, id);
        assertThat(cnt).isEqualTo(1);
    }

    @Test
    void concurrentRecordsKeepChainIntact() throws Exception {
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int k = i;
            pool.submit(() -> {
                try {
                    start.await();
                    auditService.record(1L, AuditActions.ACCOUNT_CREATED, "ADMIN_ACCOUNT",
                            "c" + k, "并发" + k);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // 串行化（advisory 锁）下：全链仍可复算、无分叉，且 prevHash 全局唯一（无两行共享同一前驱）。
        assertThat(verifier.verifyAll().intact()).isTrue();
        List<AdminAuditLog> all = auditLogs.findAllByOrderByIdAsc();
        long distinctPrev = all.stream().map(AdminAuditLog::getPrevHash).distinct().count();
        assertThat(distinctPrev).isEqualTo(all.size());
    }

    @Test
    void emergencyPasswordLoginWritesAuditAndExcludesSecret() throws Exception {
        long seq = SEQ.incrementAndGet();
        String email = "emg-" + seq + "@tailtopia.test";
        String password = "Emergency#" + seq;
        AdminAccount acc = adminAccounts.save(
                AdminAccount.newSuperAdmin(email, "紧急超管", passwordEncoder.encode(password)));

        mvc.perform(post("/admin/login")
                        .param("username", email)
                        .param("password", password)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"));

        List<AdminAuditLog> hits = auditService.search(null, null, acc.getId(),
                AuditActions.EMERGENCY_LOGIN_SUCCEEDED, PageRequest.of(0, 10)).getContent();
        assertThat(hits).isNotEmpty();
        AdminAuditLog audit = hits.get(0);
        assertThat(audit.getActionType()).isEqualTo(AuditActions.EMERGENCY_LOGIN_SUCCEEDED);
        assertThat(audit.getSummary()).contains(email);
        // 护栏：审计绝不含明文密码。
        assertThat(audit.getSummary()).doesNotContain(password);
    }
}
