package com.tailtopia.admin.refund.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.refund.service.AdminRefundQueryService;
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
 * L0：退款三段职责分离门控（A-1）——客服 need 判定 {@code refund.submit}（Story 4.4）/ 主管审批
 * {@code refund.approve}（Story 4.6）/ 财务打款 {@code refund.payout}（Story 4.6）；{@code SUPER_ADMIN} 隐式全权。
 * 无对应权限 → 403（{@link AccessDeniedException}）。service 被 mock，仅验方法级安全表达式。
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
        AdminRefundQueryService adminRefundQueryService() {
            return mock(AdminRefundQueryService.class);
        }

        @Bean
        AdminRefundController controller(RefundService s, AdminRefundQueryService q) {
            return new AdminRefundController(s, q);
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

    private void approveNeed() {
        controller.approveNeed(admin(), "tok", new RedirectAttributesModelMap());
    }

    private void rejectNeed() {
        controller.rejectNeed(admin(), "tok", new RedirectAttributesModelMap());
    }

    private void approveRefund() {
        controller.approveRefund(admin(), "tok", "note", new RedirectAttributesModelMap());
    }

    private void rejectRefund() {
        controller.rejectRefund(admin(), "tok", "reason", new RedirectAttributesModelMap());
    }

    private void payout() {
        controller.payout(admin(), "tok", new RedirectAttributesModelMap());
    }

    // ---- 客服 need 判定：refund.submit（4-4）----

    @Test
    void needDecisionRequiresRefundSubmit() {
        auth("ROLE_ADMIN", "refund.approve"); // 有审批权但无提交权
        assertThatThrownBy(this::approveNeed).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(this::rejectNeed).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void needDecisionAllowedWithRefundSubmit() {
        auth("ROLE_ADMIN", "refund.submit");
        assertThatCode(this::approveNeed).doesNotThrowAnyException();
        assertThatCode(this::rejectNeed).doesNotThrowAnyException();
    }

    // ---- 主管审批：refund.approve（4-6）----

    @Test
    void approvalRequiresRefundApprove() {
        auth("ROLE_ADMIN", "refund.submit"); // 有提交权但无审批权
        assertThatThrownBy(this::approveRefund).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(this::rejectRefund).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approvalAllowedWithRefundApprove() {
        auth("ROLE_ADMIN", "refund.approve");
        assertThatCode(this::approveRefund).doesNotThrowAnyException();
        assertThatCode(this::rejectRefund).doesNotThrowAnyException();
    }

    // ---- 财务打款：refund.payout（4-6）----

    @Test
    void payoutRequiresRefundPayout() {
        auth("ROLE_ADMIN", "refund.approve"); // 有审批权但无打款权
        assertThatThrownBy(this::payout).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void payoutAllowedWithRefundPayout() {
        auth("ROLE_ADMIN", "refund.payout");
        assertThatCode(this::payout).doesNotThrowAnyException();
    }

    @Test
    void superAdminAllowedForAll() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::approveNeed).doesNotThrowAnyException();
        assertThatCode(this::approveRefund).doesNotThrowAnyException();
        assertThatCode(this::payout).doesNotThrowAnyException();
    }
}
