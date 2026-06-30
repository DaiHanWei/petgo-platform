package com.tailtopia.admin.rating.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.rating.service.AdminRatingService;
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
import org.springframework.ui.ConcurrentModel;

/** L0：评分总览门控（Story 6.1 AC5）——{@code rating.view}；超管隐式全权。 */
class AdminRatingAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminRatingController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminRatingService ratingService() {
            AdminRatingService m = mock(AdminRatingService.class);
            when(m.overview(any(), any(), any())).thenReturn(List.of());
            return m;
        }

        @Bean
        AdminRatingController controller(AdminRatingService s) {
            return new AdminRatingController(s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminRatingController.class);
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

    private void overview() {
        controller.overview(null, null, null, null, new ConcurrentModel());
    }

    @Test
    void deniedWithoutRatingView() {
        auth("ROLE_ADMIN");
        assertThatThrownBy(this::overview).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allowedWithRatingView() {
        auth("ROLE_ADMIN", "rating.view");
        assertThatCode(this::overview).doesNotThrowAnyException();
    }

    @Test
    void allowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::overview).doesNotThrowAnyException();
    }
}
