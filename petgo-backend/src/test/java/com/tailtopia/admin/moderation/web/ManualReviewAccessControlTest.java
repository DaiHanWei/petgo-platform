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
import com.tailtopia.support.TestMessages;

/**
 * L0：人工审核门控分层（Story 4.3 AC3/AC8）——激活开关仅 {@code SUPER_ADMIN}；
 * 队列入口 + 处置（通过/拒绝）{@code SUPER_ADMIN} 或 {@code content.manual_review}
 * （处置额外接受历史 {@code content.takedown}；超管隐式覆盖）。
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
        com.tailtopia.admin.moderation.service.UnifiedTicketQueryService ticketQuery() {
            return org.mockito.Mockito.mock(
                    com.tailtopia.admin.moderation.service.UnifiedTicketQueryService.class);
        }

        @Bean
        ManualReviewAdminController controller(ManualReviewService r, AdminSettingsService s,
                com.tailtopia.admin.moderation.service.UnifiedTicketQueryService q) {
            return new ManualReviewAdminController(r, s, q, TestMessages.real());
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
        // 2026-08-19：queue() 增加了复核列表的筛选参数（type/status/q/page）。
        controller.queue(null, null, null, 0, null, new ConcurrentModel());
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

    /**
     * 入口对**无关权限**仍然拒绝（放宽只放开了 manual_review / takedown 两把，不是人人可进）。
     */
    @Test
    void queueDeniedWithUnrelatedAuthority() {
        auth("ROLE_ADMIN", "content.restore"); // 有别的内容权，但既非 manual_review 也非 takedown
        assertThatThrownBy(this::queue).isInstanceOf(AccessDeniedException.class);
    }

    /**
     * 🔴 2026-08-20 放宽：只拿到 {@code content.takedown} 的审核员也能打开本页。
     *
     * <p>本页混排四类，其中**三类**（内容举报 / 名称审核 / 头像审核）的处置动作要的正是
     * takedown。放宽前入口只认 manual_review，于是那三类的按钮全在这一页上、
     * 而有权按它们的人打不开这一页 —— 得同时授两个权限才能干活，且这个组合要求没处写着。
     *
     * <p>⚠️ 这条**不是**「放松了处置」：放开的只是入口。每行按钮仍各自门控，
     * takedown 本来就在 DECIDE_AUTH 与那三类端点的门里（见 approveAllowedWithTakedown）。
     * 若哪天有人把入口收回去，这条会红 —— 那正是要提醒他：收回去等于让 takedown 审核员失去
     * 全部三类的入口。
     */
    @Test
    void queueAllowedWithTakedownOnly() {
        auth("ROLE_ADMIN", "content.takedown");
        assertThatCode(this::queue).doesNotThrowAnyException();
    }

    @Test
    void queueAllowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::queue).doesNotThrowAnyException();
    }

    @Test
    void queueAllowedWithManualReview() {
        auth("ROLE_ADMIN", "content.manual_review"); // 授予人工审核权的 STAFF → 入口放行
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

    @Test
    void approveAllowedWithManualReview() {
        auth("ROLE_ADMIN", "content.manual_review"); // 人工审核权同样可处置
        assertThatCode(this::approve).doesNotThrowAnyException();
    }
}
