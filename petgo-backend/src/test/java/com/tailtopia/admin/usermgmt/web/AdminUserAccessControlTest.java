package com.tailtopia.admin.usermgmt.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.usermgmt.service.AdminUserService;
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
import com.tailtopia.support.TestMessages;

/** L0：用户搜索/详情 {@code @PreAuthorize(user.view)}（Story 3.1 AC6）。 */
class AdminUserAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminUserController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminUserService adminUserService() {
            AdminUserService m = mock(AdminUserService.class);
            when(m.search(any())).thenReturn(List.of());
            return m;
        }

        @Bean
        AdminUserController controller(AdminUserService s) {
            return new AdminUserController(s, TestMessages.real());
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminUserController.class);
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void auth(String... authorities) {
        var t = new TestingAuthenticationToken("admin", "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
                        .toArray(new SimpleGrantedAuthority[0]));
        t.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(t);
    }

    private void search() {
        // 传非空 q 走搜索分支（stub 了 search()）；页码与手机号筛选参数为新签名所需
        // （Story 11.4 加了 phone 筛选参数，null = 不筛）。
        controller.users("42", 0, null, null, new ConcurrentModel());
    }

    @Test
    void deniedWithoutUserView() {
        auth("ROLE_ADMIN");
        assertThatThrownBy(this::search).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allowedWithUserView() {
        auth("ROLE_ADMIN", "user.view");
        assertThatCode(this::search).doesNotThrowAnyException();
    }

    @Test
    void allowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::search).doesNotThrowAnyException();
    }

    // ===== Story 3.2：停用受 user.deactivate 门控 =====

    private void deactivate() {
        var admin = new com.tailtopia.admin.service.AdminUserDetails(1L, null, "a@x", null,
                com.tailtopia.admin.account.domain.AdminAccountType.SUPER_ADMIN);
        controller.deactivate(admin, 5L, "违规",
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());
    }

    @Test
    void deactivateRequiresUserDeactivateAuthority() {
        auth("ROLE_ADMIN", "user.view"); // 有查看权但无停用权
        assertThatThrownBy(this::deactivate).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deactivateWithAuthorityAllowed() {
        auth("ROLE_ADMIN", "user.deactivate");
        assertThatCode(this::deactivate).doesNotThrowAnyException();
    }

    // ===== bug 20260728-389：赠币受 user.grant_pawcoin 门控 =====

    private void grant() {
        var admin = new com.tailtopia.admin.service.AdminUserDetails(1L, null, "a@x", null,
                com.tailtopia.admin.account.domain.AdminAccountType.SUPER_ADMIN);
        controller.grantPawCoin(admin, 5L, 100L, "补偿", "tok-1",
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());
    }

    @Test
    void grantRequiresGrantPawcoinAuthority() {
        auth("ROLE_ADMIN", "user.view", "user.deactivate", "user.delete"); // 全治理权仍无赠币权
        assertThatThrownBy(this::grant).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void grantWithAuthorityAllowed() {
        auth("ROLE_ADMIN", "user.grant_pawcoin");
        assertThatCode(this::grant).doesNotThrowAnyException();
    }

    @Test
    void grantAllowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::grant).doesNotThrowAnyException();
    }

    // ===== Story 3.3：删除受 user.delete 门控 =====

    private void delete() {
        var admin = new com.tailtopia.admin.service.AdminUserDetails(1L, null, "a@x", null,
                com.tailtopia.admin.account.domain.AdminAccountType.SUPER_ADMIN);
        controller.delete(admin, 5L, "USER_REQUEST", "备注",
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap());
    }

    @Test
    void deleteRequiresUserDeleteAuthority() {
        auth("ROLE_ADMIN", "user.deactivate"); // 有停用权但无删除权
        assertThatThrownBy(this::delete).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteWithAuthorityAllowed() {
        auth("ROLE_ADMIN", "user.delete");
        assertThatCode(this::delete).doesNotThrowAnyException();
    }
}
