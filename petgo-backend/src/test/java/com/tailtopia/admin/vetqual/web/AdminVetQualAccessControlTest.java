package com.tailtopia.admin.vetqual.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminVetService;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.shared.media.SignedUrlService;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** L0：资质端点 {@code @PreAuthorize(vet.qualify)}（Story 2.7 AC5）——vet.qualify/超管放行，否则 403。 */
class AdminVetQualAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminVetQualificationController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        VetQualificationService qualService() {
            VetQualificationService m = mock(VetQualificationService.class);
            when(m.findForVet(anyLong())).thenReturn(Optional.empty());
            return m;
        }

        @Bean
        AdminVetService adminVetService() {
            return mock(AdminVetService.class);
        }

        @Bean
        SignedUrlService signedUrlService() {
            return mock(SignedUrlService.class);
        }

        @Bean
        AdminVetQualificationController controller(VetQualificationService q, AdminVetService v,
                SignedUrlService s) {
            return new AdminVetQualificationController(q, v, s);
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminVetQualificationController.class);
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

    private AdminUserDetails principal() {
        return new AdminUserDetails(1L, null, "a@x", null, AdminAccountType.SUPER_ADMIN);
    }

    private void approve() {
        controller.approve(principal(), 5L, new RedirectAttributesModelMap());
    }

    @Test
    void approveDeniedWithoutVetQualify() {
        auth("ROLE_ADMIN", "vet.view");
        assertThatThrownBy(this::approve).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approveAllowedWithVetQualify() {
        auth("ROLE_ADMIN", "vet.qualify");
        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void approveAllowedForSuperAdmin() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::approve).doesNotThrowAnyException();
    }
}
