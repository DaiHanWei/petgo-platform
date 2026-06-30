package com.tailtopia.admin.failedrequest.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.failedrequest.service.FailedConsultRequestService;
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

/** L0：失败请求队列页 {@code @PreAuthorize(vet.view)}（Story 2.9 AC5）。 */
class FailedRequestAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static FailedRequestAdminController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        FailedConsultRequestService service() {
            FailedConsultRequestService m = mock(FailedConsultRequestService.class);
            when(m.active()).thenReturn(List.of());
            when(m.archived()).thenReturn(List.of());
            return m;
        }

        @Bean
        FailedRequestAdminController controller(FailedConsultRequestService s) {
            return new FailedRequestAdminController(s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(FailedRequestAdminController.class);
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
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
                        .toArray(new SimpleGrantedAuthority[0]));
        t.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(t);
    }

    private void list() {
        controller.list(new ConcurrentModel());
    }

    @Test
    void deniedWithoutVetView() {
        auth("ROLE_ADMIN");
        assertThatThrownBy(this::list).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allowedWithVetView() {
        auth("ROLE_ADMIN", "vet.view");
        assertThatCode(this::list).doesNotThrowAnyException();
    }

    @Test
    void allowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::list).doesNotThrowAnyException();
    }
}
