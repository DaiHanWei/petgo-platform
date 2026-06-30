package com.tailtopia.admin.moderation.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.moderation.service.AdminContentManageService;
import com.tailtopia.admin.service.AdminUserDetails;
import java.util.Arrays;
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
 * L0：内容管理门控（Story 4.2 AC7）——浏览/下架 {@code content.proactive_takedown}、
 * 恢复 {@code content.restore}；{@code SUPER_ADMIN} 隐式全权。
 */
class AdminContentManageAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminContentManageController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminContentManageService contentManage() {
            return mock(AdminContentManageService.class);
        }

        @Bean
        AdminContentManageController controller(AdminContentManageService s) {
            return new AdminContentManageController(s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminContentManageController.class);
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
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
                        .toArray(new SimpleGrantedAuthority[0]));
        t.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(t);
    }

    private static AdminUserDetails admin() {
        return new AdminUserDetails(1L, null, "a@x", null, AdminAccountType.SUPER_ADMIN);
    }

    private void browse() {
        controller.content(null, null, null, null, null, null, 0, null, new ConcurrentModel());
    }

    private void takedown() {
        controller.takedown(admin(), 5L, "违规", new RedirectAttributesModelMap());
    }

    private void restore() {
        controller.restore(admin(), 5L, new RedirectAttributesModelMap());
    }

    @Test
    void browseDeniedWithoutAuthority() {
        auth("ROLE_ADMIN");
        assertThatThrownBy(this::browse).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void browseAllowedWithProactiveTakedown() {
        auth("ROLE_ADMIN", "content.proactive_takedown");
        assertThatCode(this::browse).doesNotThrowAnyException();
    }

    @Test
    void browseAllowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::browse).doesNotThrowAnyException();
    }

    @Test
    void takedownRequiresProactiveTakedown() {
        auth("ROLE_ADMIN", "content.restore"); // 有恢复权但无下架权
        assertThatThrownBy(this::takedown).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void takedownAllowedWithProactiveTakedown() {
        auth("ROLE_ADMIN", "content.proactive_takedown");
        assertThatCode(this::takedown).doesNotThrowAnyException();
    }

    @Test
    void restoreRequiresRestoreAuthority() {
        auth("ROLE_ADMIN", "content.proactive_takedown"); // 有下架权但无恢复权
        assertThatThrownBy(this::restore).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void restoreAllowedWithRestoreAuthority() {
        auth("ROLE_ADMIN", "content.restore");
        assertThatCode(this::restore).doesNotThrowAnyException();
    }
}
