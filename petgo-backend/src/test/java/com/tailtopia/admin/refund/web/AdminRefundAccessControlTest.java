package com.tailtopia.admin.refund.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.pay.refund.service.RefundService;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * L0：客服退款判定门控（Story 4.4 AC4）——批准/驳回均需 {@code refund.submit}；{@code SUPER_ADMIN} 隐式全权。
 * 无权 → 403（{@link AccessDeniedException}）。service 被 mock，仅验方法级安全表达式。
 */
class AdminRefundAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminRefundController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        RefundService refundService() {
            return mock(RefundService.class);
        }

        @Bean
        AdminRefundController controller(RefundService s) {
            return new AdminRefundController(s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminRefundController.class);
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

    private void approve() {
        controller.approve(admin(), "tok-approve", new RedirectAttributesModelMap());
    }

    private void reject() {
        controller.reject(admin(), "tok-reject", new RedirectAttributesModelMap());
    }

    @Test
    void approveDeniedWithoutRefundSubmit() {
        auth("ROLE_ADMIN", "refund.approve"); // 有审批权但无提交权
        assertThatThrownBy(this::approve).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approveAllowedWithRefundSubmit() {
        auth("ROLE_ADMIN", "refund.submit");
        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void approveAllowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void rejectDeniedWithoutRefundSubmit() {
        auth("ROLE_ADMIN", "refund.payout"); // 有打款权但无提交权
        assertThatThrownBy(this::reject).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectAllowedWithRefundSubmit() {
        auth("ROLE_ADMIN", "refund.submit");
        assertThatCode(this::reject).doesNotThrowAnyException();
    }
}
