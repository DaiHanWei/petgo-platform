package com.tailtopia.admin.anomaly.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.service.ConsultSessionAdminQueryService;
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

/** L0：会话查询门控（Story 5.2 AC6）——{@code consult.view_sessions}；超管隐式全权。 */
class AdminConsultSessionAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminConsultSessionController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        ConsultSessionAdminQueryService queryService() {
            ConsultSessionAdminQueryService m = mock(ConsultSessionAdminQueryService.class);
            when(m.search(any(), any(), any(), any())).thenReturn(List.of());
            return m;
        }

        @Bean
        AdminConsultSessionController controller(ConsultSessionAdminQueryService s) {
            return new AdminConsultSessionController(s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminConsultSessionController.class);
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

    private void search() {
        controller.search(null, null, null, null, null, new ConcurrentModel());
    }

    @Test
    void deniedWithoutViewSessions() {
        auth("ROLE_ADMIN");
        assertThatThrownBy(this::search).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allowedWithViewSessions() {
        auth("ROLE_ADMIN", "consult.view_sessions");
        assertThatCode(this::search).doesNotThrowAnyException();
    }

    @Test
    void allowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::search).doesNotThrowAnyException();
    }
}
