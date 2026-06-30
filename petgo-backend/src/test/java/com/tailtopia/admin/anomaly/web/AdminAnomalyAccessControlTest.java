package com.tailtopia.admin.anomaly.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.anomaly.domain.AnomalyType;
import com.tailtopia.admin.anomaly.domain.ConsultAnomaly;
import com.tailtopia.admin.anomaly.service.ConsultAnomalyService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.media.SignedUrlService;
import java.util.Arrays;
import java.util.Optional;
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
 * L0：异常工单门控（Story 5.1 AC9）——查看 {@code consult.view_anomalies}；处理（备注/标记已处理）
 * {@code consult.handle}；超管隐式全权。
 */
class AdminAnomalyAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminAnomalyController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        ConsultAnomalyService anomalyService() {
            ConsultAnomalyService m = mock(ConsultAnomalyService.class);
            when(m.find(anyLong())).thenReturn(Optional.of(
                    ConsultAnomaly.open(1L, 2L, 3L, null, null, "INTERRUPTED", AnomalyType.VET_BANNED)));
            return m;
        }

        @Bean
        SignedUrlService signedUrlService() {
            return mock(SignedUrlService.class);
        }

        @Bean
        AdminAnomalyController controller(ConsultAnomalyService a, SignedUrlService s) {
            return new AdminAnomalyController(a, s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminAnomalyController.class);
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

    private void list() {
        controller.list(null, null, new ConcurrentModel());
    }

    private void note() {
        controller.note(admin(), 1L, "备注", new RedirectAttributesModelMap());
    }

    private void resolve() {
        controller.resolve(admin(), 1L, null, new RedirectAttributesModelMap());
    }

    @Test
    void listDeniedWithoutViewAnomalies() {
        auth("ROLE_ADMIN");
        assertThatThrownBy(this::list).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listAllowedWithViewAnomalies() {
        auth("ROLE_ADMIN", "consult.view_anomalies");
        assertThatCode(this::list).doesNotThrowAnyException();
    }

    @Test
    void listAllowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::list).doesNotThrowAnyException();
    }

    @Test
    void noteRequiresHandleAuthority() {
        auth("ROLE_ADMIN", "consult.view_anomalies"); // 仅查看权
        assertThatThrownBy(this::note).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noteAllowedWithHandle() {
        auth("ROLE_ADMIN", "consult.handle");
        assertThatCode(this::note).doesNotThrowAnyException();
    }

    @Test
    void resolveRequiresHandleAuthority() {
        auth("ROLE_ADMIN", "consult.view_anomalies");
        assertThatThrownBy(this::resolve).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void resolveAllowedWithHandle() {
        auth("ROLE_ADMIN", "consult.handle");
        assertThatCode(this::resolve).doesNotThrowAnyException();
    }
}
