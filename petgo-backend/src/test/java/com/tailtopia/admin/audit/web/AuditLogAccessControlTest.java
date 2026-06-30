package com.tailtopia.admin.audit.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.service.AdminAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;

/**
 * L0：审计页 {@code @PreAuthorize} 门控（AC5/T5）——真启用方法级安全的最小上下文，
 * 验证「SUPER_ADMIN 或 admin.view_logs 放行；仅 ROLE_ADMIN 的 STAFF → 403(AccessDenied)」。无需 DB/Web 层。
 */
class AuditLogAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AuditLogAdminController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminAuditService auditService() {
            AdminAuditService m = mock(AdminAuditService.class);
            when(m.search(any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(Page.<AdminAuditLog>empty());
            return m;
        }

        @Bean
        AuditLogAdminController auditLogAdminController(AdminAuditService s) {
            return new AuditLogAdminController(s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AuditLogAdminController.class);
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWith(String... authorities) {
        var token = new TestingAuthenticationToken("admin", "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
                        .toArray(new SimpleGrantedAuthority[0]));
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private void invoke() {
        controller.auditLogs(null, null, null, null, 0, null, new ConcurrentModel());
    }

    @Test
    void staffWithoutViewLogsIsDenied() {
        authenticateWith("ROLE_ADMIN");
        assertThatThrownBy(this::invoke).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void superAdminAllowed() {
        authenticateWith("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::invoke).doesNotThrowAnyException();
    }

    @Test
    void staffWithViewLogsAuthorityAllowed() {
        authenticateWith("ROLE_ADMIN", "admin.view_logs");
        assertThatCode(this::invoke).doesNotThrowAnyException();
    }

    @Test
    void unrelatedAuthorityDenied() {
        authenticateWith("ROLE_ADMIN", "admin.view_vets");
        assertThatThrownBy(this::invoke).isInstanceOf(AccessDeniedException.class);
    }
}
