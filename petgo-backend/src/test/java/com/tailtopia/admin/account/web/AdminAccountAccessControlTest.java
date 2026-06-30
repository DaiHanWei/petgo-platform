package com.tailtopia.admin.account.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.service.AdminAccountService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.account.domain.AdminAccountType;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * L0：账号管理端点 {@code @PreAuthorize} 门控（AC6）——两道独立权限门：
 * 查看/创建 = {@code admin.create_account}；停用/激活 = {@code admin.deactivate}；SUPER_ADMIN 隐式通过。
 */
class AdminAccountAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminAccountAdminController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminAccountService accountService() {
            AdminAccountService m = mock(AdminAccountService.class);
            when(m.list()).thenReturn(List.of());
            return m;
        }

        @Bean
        AdminAccountAdminController adminAccountAdminController(AdminAccountService s) {
            return new AdminAccountAdminController(s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminAccountAdminController.class);
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWith(String... authorities) {
        var token = new TestingAuthenticationToken("admin", "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
                        .toArray(new SimpleGrantedAuthority[0]));
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static AdminUserDetails principal() {
        return new AdminUserDetails(1L, null, "a@x", null, AdminAccountType.SUPER_ADMIN);
    }

    private void viewList() {
        controller.accounts(new ConcurrentModel());
    }

    private void deactivate() {
        controller.deactivate(principal(), 5L, new RedirectAttributesModelMap());
    }

    @Test
    void viewRequiresCreateAccountAuthority() {
        authenticateWith("ROLE_ADMIN"); // STAFF 无 admin.create_account
        assertThatThrownBy(this::viewList).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createAccountAuthorityCanView() {
        authenticateWith("ROLE_ADMIN", "admin.create_account");
        assertThatCode(this::viewList).doesNotThrowAnyException();
    }

    @Test
    void superAdminCanView() {
        authenticateWith("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::viewList).doesNotThrowAnyException();
    }

    @Test
    void deactivateNeedsDeactivateAuthorityNotCreate() {
        authenticateWith("ROLE_ADMIN", "admin.create_account"); // 有创建权但无停用权
        assertThatThrownBy(this::deactivate).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deactivateAuthorityAllowed() {
        authenticateWith("ROLE_ADMIN", "admin.deactivate");
        assertThatCode(this::deactivate).doesNotThrowAnyException();
    }
}
