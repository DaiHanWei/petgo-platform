package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.mockito.ArgumentMatchers.anyLong;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.dto.CreateVetForm;
import com.tailtopia.admin.dto.EditVetForm;
import com.tailtopia.admin.service.AdminContentService;
import com.tailtopia.admin.service.AdminModerationService;
import com.tailtopia.admin.service.AdminVetService;
import com.tailtopia.admin.service.AdminUserDetails;
import java.util.List;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
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

/** L0：兽医列表端点 {@code @PreAuthorize}（Story 2.2 AC4）——vet.view 或 SUPER_ADMIN 放行，否则 403。 */
class AdminVetsAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminWebController controller;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminVetService adminVetService() {
            AdminVetService m = mock(AdminVetService.class);
            when(m.list(any())).thenReturn(List.of());
            when(m.create(any(), any(), any(), any(), anyLong())).thenReturn(1L);
            when(m.editForm(anyLong())).thenReturn(new EditVetForm());
            return m;
        }

        @Bean
        AdminWebController adminWebController(AdminVetService vet) {
            return new AdminWebController(mock(AdminContentService.class),
                    mock(AdminModerationService.class), vet,
                    mock(com.tailtopia.admin.dashboard.service.AdminDashboardService.class),
                    mock(com.tailtopia.admin.virtual.service.AdminVirtualAccountService.class));
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
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
                        .toArray(new SimpleGrantedAuthority[0]));
        t.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(t);
    }

    private void invoke() {
        controller.vets(null, null, null, null, null, new ConcurrentModel());
    }

    @Test
    void withoutVetViewIsDenied() {
        auth("ROLE_ADMIN");
        assertThatThrownBy(this::invoke).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void vetViewAuthorityAllowed() {
        auth("ROLE_ADMIN", "vet.view");
        assertThatCode(this::invoke).doesNotThrowAnyException();
    }

    @Test
    void superAdminAllowed() {
        auth("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(this::invoke).doesNotThrowAnyException();
    }

    // ===== Story 2.3：createVet 受 vet.create 门控 =====

    private void createVet() {
        AdminUserDetails admin = new AdminUserDetails(1L, null, "a@x", null, AdminAccountType.SUPER_ADMIN);
        CreateVetForm form = new CreateVetForm();
        form.setDisplayName("Dr X");
        form.setUsername("dr@x");
        form.setContactPhone("+62-811");
        form.setPassword("Secret#123");
        controller.createVet(admin, form, new BeanPropertyBindingResult(form, "createVetForm"),
                new ConcurrentModel());
    }

    @Test
    void createVetWithoutVetCreateIsDenied() {
        auth("ROLE_ADMIN", "vet.view"); // 有查看权但无 create 权
        assertThatThrownBy(this::createVet).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createVetWithVetCreateAuthorityAllowed() {
        auth("ROLE_ADMIN", "vet.create");
        assertThatCode(this::createVet).doesNotThrowAnyException();
    }

    // ===== Story 2.4：编辑(vet.create) / 重置密码(vet.reset_password) 门控 =====

    private AdminUserDetails principal() {
        return new AdminUserDetails(1L, null, "a@x", null, AdminAccountType.SUPER_ADMIN);
    }

    private void updateVet() {
        EditVetForm form = new EditVetForm();
        form.setDisplayName("Dr Y");
        form.setUsername("dry@x");
        controller.updateVet(principal(), 5L, form,
                new BeanPropertyBindingResult(form, "editVetForm"),
                new ConcurrentModel(), new RedirectAttributesModelMap());
    }

    private void resetPassword() {
        controller.resetVetPassword(principal(), 5L, "NewPass#1", new RedirectAttributesModelMap());
    }

    @Test
    void editRequiresVetCreate() {
        auth("ROLE_ADMIN", "vet.reset_password"); // 有重置权但无编辑权
        assertThatThrownBy(this::updateVet).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void editWithVetCreateAllowed() {
        auth("ROLE_ADMIN", "vet.create");
        assertThatCode(this::updateVet).doesNotThrowAnyException();
    }

    @Test
    void resetRequiresResetPasswordAuthority() {
        auth("ROLE_ADMIN", "vet.create"); // 有编辑权但无重置权
        assertThatThrownBy(this::resetPassword).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void resetWithResetPasswordAuthorityAllowed() {
        auth("ROLE_ADMIN", "vet.reset_password");
        assertThatCode(this::resetPassword).doesNotThrowAnyException();
    }

    // ===== Story 2.5：封禁/解封受 vet.ban 门控 =====

    private void setBanned() {
        controller.setVetStatus(principal(), 5L, true, new RedirectAttributesModelMap());
    }

    @Test
    void banRequiresVetBanAuthority() {
        auth("ROLE_ADMIN", "vet.create"); // 无 vet.ban
        assertThatThrownBy(this::setBanned).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void banWithVetBanAuthorityAllowed() {
        auth("ROLE_ADMIN", "vet.ban");
        assertThatCode(this::setBanned).doesNotThrowAnyException();
    }
}
