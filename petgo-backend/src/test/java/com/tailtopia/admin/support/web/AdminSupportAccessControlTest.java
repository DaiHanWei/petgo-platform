package com.tailtopia.admin.support.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.support.service.AdminSupportTicketQueryService;
import com.tailtopia.support.service.SupportTicketService;
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
 * L0：客服工单后台门控（Story 4.7）——列表/详情/结案均需 {@code support.handle}；{@code SUPER_ADMIN} 隐式全权。
 * 无权 → 403（{@link AccessDeniedException}）。service 被 mock，仅验方法级安全表达式。
 */
class AdminSupportAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminSupportTicketController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminSupportTicketQueryService query() {
            return mock(AdminSupportTicketQueryService.class);
        }

        @Bean
        SupportTicketService ticketService() {
            return mock(SupportTicketService.class);
        }

        @Bean
        AdminSupportTicketController controller(AdminSupportTicketQueryService q, SupportTicketService s) {
            return new AdminSupportTicketController(q, s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminSupportTicketController.class);
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

    private void list() {
        controller.list(new ConcurrentModel());
    }

    private void resolve() {
        controller.resolve(admin(), "tok", new RedirectAttributesModelMap());
    }

    @Test
    void deniedWithoutSupportHandle() {
        auth("ROLE_ADMIN", "refund.submit"); // 无关权限
        assertThatThrownBy(this::list).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(this::resolve).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allowedWithSupportHandle() {
        auth("ROLE_ADMIN", "support.handle");
        assertThatCode(this::list).doesNotThrowAnyException();
        assertThatCode(this::resolve).doesNotThrowAnyException();
    }

    @Test
    void allowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::list).doesNotThrowAnyException();
        assertThatCode(this::resolve).doesNotThrowAnyException();
    }
}
