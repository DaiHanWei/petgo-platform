package com.tailtopia.admin.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.growth.service.AdminGrowthUserService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.support.TestMessages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** V1.4.0 ① Story 4.2 · AC8.4（L0）：明细门 = user.view；两个调整端点门 = growth.user_adjust（直接 POST 无码 403）。 */
class AdminGrowthUserAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminGrowthUserController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminGrowthUserController controller() {
            return new AdminGrowthUserController(mock(AdminGrowthUserService.class), TestMessages.real());
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminGrowthUserController.class);
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void auth(String... authorities) {
        var t = new TestingAuthenticationToken("admin", "n/a", authorities);
        t.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(t);
    }

    private void ledger() {
        controller.ledger(7L, null, null, null, null, HxRequest.NONE, new ConcurrentModel());
    }

    private void confirm() {
        controller.confirm(7L, "ADMIN_ADJUST", "5", "x", "tok", HxRequest.NONE, new ConcurrentModel(),
                new MockHttpServletResponse());
    }

    private void adjust() {
        var admin = new AdminUserDetails(1L, null, "a@x", null, AdminAccountType.SUPER_ADMIN);
        controller.adjust(admin, 7L, "ADMIN_ADJUST", "5", "x", "tok", HxRequest.NONE, new ConcurrentModel(),
                new MockHttpServletResponse(), new RedirectAttributesModelMap());
    }

    @Test
    void ledgerNeedsUserView() {
        auth("ROLE_ADMIN");
        assertThatThrownBy(this::ledger).isInstanceOf(AccessDeniedException.class);
        auth("ROLE_ADMIN", "user.view");
        assertThatCode(this::ledger).doesNotThrowAnyException();
    }

    @Test
    void adjustEndpointsDenyUserViewOnly() {
        auth("ROLE_ADMIN", "user.view", "user.grant_pawcoin", "growth.config_edit");
        assertThatThrownBy(this::confirm).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(this::adjust).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adjustEndpointsAllowGrowthUserAdjustAndSuperAdmin() {
        auth("ROLE_ADMIN", "growth.user_adjust");
        assertThatCode(this::confirm).doesNotThrowAnyException();
        assertThatCode(this::adjust).doesNotThrowAnyException();
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::adjust).doesNotThrowAnyException();
    }

    @Test
    void gateExpressionsNameTheRealCodes() {
        assertThat(AdminGrowthUserController.GROWTH_ADJUST_AUTH).contains("'growth.user_adjust'");
        assertThat(AdminGrowthUserController.VIEW_AUTH).contains("'user.view'");
    }

    @Test
    void officialDisplayLevelNeedsVirtualAccountManage() {
        var admin = new AdminUserDetails(1L, null, "a@x", null, AdminAccountType.SUPER_ADMIN);
        auth("ROLE_ADMIN", "user.view", "growth.user_adjust");
        assertThatThrownBy(() -> controller.setDisplayLevel(admin, 7L, "3", HxRequest.NONE, new ConcurrentModel(),
                new MockHttpServletResponse(), new RedirectAttributesModelMap())).isInstanceOf(AccessDeniedException.class);
        auth("ROLE_ADMIN", "virtual_account.manage");
        assertThatCode(() -> controller.setDisplayLevel(admin, 7L, "3", HxRequest.NONE, new ConcurrentModel(),
                new MockHttpServletResponse(), new RedirectAttributesModelMap())).doesNotThrowAnyException();
    }
}
