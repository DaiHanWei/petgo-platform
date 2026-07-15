package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.service.AdminContentService;
import com.tailtopia.admin.service.AdminModerationService;
import com.tailtopia.admin.service.AdminVetService;
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

/** L0：单兽医评分详情门控（Story 6.2 AC5）——{@code rating.view}；超管隐式全权。 */
class AdminVetRatingsAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminWebController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminContentService adminContentService() {
            return mock(AdminContentService.class);
        }

        @Bean
        AdminModerationService adminModerationService() {
            return mock(AdminModerationService.class);
        }

        @Bean
        AdminVetService adminVetService() {
            return mock(AdminVetService.class);
        }

        @Bean
        AdminWebController controller(AdminContentService c, AdminModerationService m, AdminVetService v) {
            return new AdminWebController(c, m, v, mock(com.tailtopia.admin.dashboard.service.AdminDashboardService.class));
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminWebController.class);
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

    private void vetRatings() {
        controller.vetRatings(5L, new ConcurrentModel());
    }

    @Test
    void deniedWithoutRatingView() {
        auth("ROLE_ADMIN");
        assertThatThrownBy(this::vetRatings).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allowedWithRatingView() {
        auth("ROLE_ADMIN", "rating.view");
        assertThatCode(this::vetRatings).doesNotThrowAnyException();
    }

    @Test
    void allowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::vetRatings).doesNotThrowAnyException();
    }
}
