package com.tailtopia.admin.moderation.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.moderation.read.ViolationCountReader;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.avatarmoderation.service.AvatarModerationService;
import com.tailtopia.namemoderation.service.NameModerationService;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.util.Arrays;
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

/**
 * L0：名称/头像处置控制台门控（story 8，AC13）——页面 + 头像处置均 {@code SUPER_ADMIN or content.takedown}
 * （展示审核证据，§5.5）。方法安全切片，无 DB。
 *
 * <p>🔴 <b>2026-08-19：页面已删除（列表并入「人工复核」），故原三条「页面门控」用例随之作废、已移除。</b>
 * 本类保留的是<b>头像处置端点</b>的门控 —— 该端点仅此一处，是头像判违规的唯一入口。
 */
class NameAvatarReviewAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static NameAvatarReviewAdminController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        NameModerationService nameService() {
            NameModerationService m = mock(NameModerationService.class);
            when(m.pendingQueue()).thenReturn(List.of());
            return m;
        }

        @Bean
        AvatarModerationService avatarService() {
            AvatarModerationService m = mock(AvatarModerationService.class);
            when(m.pendingQueue()).thenReturn(List.of());
            return m;
        }

        @Bean
        ViolationCountReader violationCounts() {
            return mock(ViolationCountReader.class);
        }

        @Bean
        PetProfileRepository petProfiles() {
            return mock(PetProfileRepository.class);
        }

        @Bean
        NameAvatarReviewAdminController controller(AvatarModerationService a) {
            return new NameAvatarReviewAdminController(a);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(NameAvatarReviewAdminController.class);
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

    private void decideAvatar() {
        controller.decideAvatar(admin(), 5L, "PASS", "OTHER", null);
    }

    @Test
    void decideAvatarDeniedWithoutTakedown() {
        auth("ROLE_ADMIN", "content.restore");
        assertThatThrownBy(this::decideAvatar).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void decideAvatarAllowedWithTakedown() {
        auth("ROLE_ADMIN", "content.takedown");
        assertThatCode(this::decideAvatar).doesNotThrowAnyException();
    }
}
