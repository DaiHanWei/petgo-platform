package com.tailtopia.admin.roles.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.roles.dto.PermissionMatrixView;
import com.tailtopia.admin.roles.dto.RoleChange;
import com.tailtopia.admin.roles.service.AdminRoleService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.TestMessages;
import java.util.List;
import java.util.Set;
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

/** L0：角色配置端点全部仅超管（Story 1.5 AC1）；PRG 与 flash。 */
class AdminRoleAccessControlTest {

    private static AnnotationConfigApplicationContext ctx;
    private static AdminRoleController controller;
    private static AdminRoleService service;

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        AdminRoleService roleService() {
            AdminRoleService m = mock(AdminRoleService.class);
            when(m.list()).thenReturn(List.of());
            when(m.matrix(any())).thenReturn(new PermissionMatrixView(List.of(), Set.of()));
            when(m.updatePermissions(anyLong(), any(), anyLong())).thenReturn(new RoleChange(1L, "FINANCE", 1, 0, 2));
            when(m.updateCustom(anyLong(), any(), any(), anyLong())).thenReturn(new RoleChange(7L, "role-7", 0, 1, 1));
            return m;
        }

        @Bean
        AdminRoleController adminRoleController(AdminRoleService s) {
            return new AdminRoleController(s, TestMessages.real());
        }
    }

    @BeforeAll
    static void start() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = ctx.getBean(AdminRoleController.class);
        service = ctx.getBean(AdminRoleService.class);
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWith(String... authorities) {
        var token = new TestingAuthenticationToken("admin", "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()
                        .toArray(new SimpleGrantedAuthority[0]));
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static AdminUserDetails principal() {
        return new AdminUserDetails(1L, null, "a@x", null, AdminAccountType.SUPER_ADMIN);
    }

    @Test
    void everyEndpointRejectsNonSuperAdminEvenWithAccountAuthorities() {
        authenticateWith("ROLE_ADMIN", "admin.create_account", "admin.deactivate", "admin.view_accounts");
        assertThatThrownBy(() -> controller.list(new ConcurrentModel())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.createForm(new ConcurrentModel())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.editForm(1L, new ConcurrentModel())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.create(principal(), "x", List.of("vet.view"), new RedirectAttributesModelMap()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.update(principal(), 1L, "x", List.of("vet.view"), new RedirectAttributesModelMap()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.updatePermissions(principal(), 1L, List.of("vet.view"), new RedirectAttributesModelMap()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.delete(principal(), 1L, new RedirectAttributesModelMap()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void superAdminAllowedAndSavesRedirectToList() {
        authenticateWith("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThatCode(() -> controller.list(new ConcurrentModel())).doesNotThrowAnyException();
        var flash = new RedirectAttributesModelMap();
        assertThat(controller.updatePermissions(principal(), 1L, List.of("vet.view"), flash)).isEqualTo("redirect:/admin/roles");
        assertThat(flash.getFlashAttributes()).containsKey("notice");
        assertThat(String.valueOf(flash.getFlashAttributes().get("notice"))).contains("FINANCE");
    }

    @Test
    void serviceErrorBecomesFlashErrorAndStaysOnForm() {
        authenticateWith("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        when(service.create(any(), any(), anyLong()))
                .thenThrow(AppException.validation("至少勾选 1 项权限").code("admin.err.role.noPermission"));
        var flash = new RedirectAttributesModelMap();
        assertThat(controller.create(principal(), "x", List.of(), flash)).isEqualTo("redirect:/admin/roles/new");
        assertThat(flash.getFlashAttributes()).containsKey("error");
    }
}
