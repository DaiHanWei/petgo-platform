package com.tailtopia.admin.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.domain.AuditHashing;
import com.tailtopia.admin.audit.repository.AdminAuditLogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** L0：统一审计入口 + 哈希链（AC2/AC3，mock repo + mock 锁，无需 DB）。 */
class AdminAuditServiceTest {

    private AdminAuditLogRepository repo;
    private AuditChainLock chainLock;
    private AdminAuditService service;

    @BeforeEach
    void setUp() {
        repo = mock(AdminAuditLogRepository.class);
        chainLock = mock(AuditChainLock.class);
        service = new AdminAuditService(repo, chainLock);
        when(repo.save(any(AdminAuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void firstRecordUsesGenesisPrevHashAndAcquiresLock() {
        when(repo.findTopByOrderByIdDesc()).thenReturn(Optional.empty());

        AdminAuditLog saved = service.record(7L, "EMERGENCY_LOGIN_SUCCEEDED", "ADMIN_ACCOUNT", "7", "登录");

        // AG-2：先拿串行化锁。
        verify(chainLock).acquire();
        assertThat(saved.getPrevHash()).isEqualTo(AuditHashing.GENESIS_HASH);
        // rowHash 必须与按落库字段重算一致（链可独立复算）。
        assertThat(saved.getRowHash()).isEqualTo(AuditHashing.rowHash(saved));
        // createdAt 截断到微秒（与 timestamptz 精度对齐，回读可复算）。
        assertThat(saved.getCreatedAt()).isEqualTo(saved.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void secondRecordChainsOnPreviousRowHash() {
        AdminAuditLog tail = AdminAuditLog.create(1L, "ACCOUNT_CREATED", "ADMIN_ACCOUNT", "1", "s",
                Instant.parse("2026-06-29T00:00:00Z"), AuditHashing.GENESIS_HASH, "abc123tail");
        when(repo.findTopByOrderByIdDesc()).thenReturn(Optional.of(tail));

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        service.record(2L, "VET_BANNED", "VET", "v_token", "封禁");

        verify(repo).save(captor.capture());
        AdminAuditLog appended = captor.getValue();
        // 本行 prevHash = 链尾 rowHash（链接续）。
        assertThat(appended.getPrevHash()).isEqualTo("abc123tail");
        assertThat(appended.getRowHash()).isEqualTo(AuditHashing.rowHash(appended));
    }
}
