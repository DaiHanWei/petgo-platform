package com.tailtopia.admin.moderation.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.moderation.service.AdminSettingsService;
import com.tailtopia.admin.moderation.service.ManualReviewService;
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
 * L0：人工审核门控分层（Story 4.3 AC3/AC8）——队列入口 + 开关仅 {@code SUPER_ADMIN}；
 * 处置（通过/拒绝）{@code content.takedown}（超管隐式覆盖）。
 */
class ManualReviewAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static ManualReviewAdminController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        ManualReviewService reviewService() {
            return mock(ManualReviewService.class);
        }

        @Bean
        AdminSettingsService settingsService() {
            return mock(AdminSettingsService.class);
        }

        @Bean
        ManualReviewAdminController controller(ManualReviewService r, AdminSettingsService s) {
            return new ManualReviewAdminController(r, s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(ManualReviewAdminController.class);
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

    private void queue() {
        controller.queue(null, new ConcurrentModel());
    }

    private void toggle() {
        controller.toggle(admin(), true, new RedirectAttributesModelMap());
    }

    private void approve() {
        controller.approve(admin(), 5L, new RedirectAttributesModelMap());
    }

    private void changePriority() {
        controller.changePriority(admin(), 5L, "P0", new RedirectAttributesModelMap());
    }

    @Test
    void queueDeniedForNonSuperAdmin() {
        auth("ROLE_ADMIN", "content.takedown"); // 有处置权但非超管 → 入口仍拒
        assertThatThrownBy(this::queue).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void queueAllowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::queue).doesNotThrowAnyException();
    }

    @Test
    void toggleDeniedForNonSuperAdmin() {
        auth("ROLE_ADMIN", "content.takedown");
        assertThatThrownBy(this::toggle).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void toggleAllowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::toggle).doesNotThrowAnyException();
    }

    @Test
    void approveDeniedWithoutTakedown() {
        auth("ROLE_ADMIN", "content.restore"); // 无 content.takedown 且非超管
        assertThatThrownBy(this::approve).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approveAllowedWithTakedown() {
        auth("ROLE_ADMIN", "content.takedown");
        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void approveAllowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    // story 8：改优先级为处置权（content.takedown / 超管），与通过/拒绝同级（AC4/AC13）。
    @Test
    void changePriorityDeniedWithoutTakedown() {
        auth("ROLE_ADMIN", "content.restore");
        assertThatThrownBy(this::changePriority).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void changePriorityAllowedWithTakedown() {
        auth("ROLE_ADMIN", "content.takedown");
        assertThatCode(this::changePriority).doesNotThrowAnyException();
    }
}
