package com.tailtopia.admin.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.dto.LarkIdentity;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** L0：Lark 登录门控（AC3/AC4）—— 租户/邮箱验证/白名单匹配的接受与各拒绝分支。 */
class AdminLarkAuthServiceTest {

    private static final String TENANT = "tenant-123";
    private AdminUserDetailsService userDetailsService;
    private AdminLarkAuthService auth;

    @BeforeEach
    void setUp() {
        userDetailsService = mock(AdminUserDetailsService.class);
        auth = new AdminLarkAuthService(userDetailsService, TENANT);
    }

    private AdminUserDetails details() {
        return new AdminUserDetails(7L, 99L, "ops@corp.com", null, AdminAccountType.STAFF);
    }

    @Test
    void acceptsWhenTenantVerifiedAndWhitelisted() {
        when(userDetailsService.loadByEmail("ops@corp.com", false)).thenReturn(details());
        LarkIdentity id = new LarkIdentity(null, "ops@corp.com", TENANT, "ou_1", true);
        assertThat(auth.authenticate(id)).isPresent();
    }

    @Test
    void rejectsWrongTenant() {
        LarkIdentity id = new LarkIdentity(null, "ops@corp.com", "tenant-OTHER", "ou_1", true);
        assertThat(auth.authenticate(id)).isEmpty();
        verify(userDetailsService, never()).loadByEmail(ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
    }

    @Test
    void rejectsUnverifiedEmail() {
        LarkIdentity id = new LarkIdentity("ops@corp.com", null, TENANT, "ou_1", false);
        assertThat(auth.authenticate(id)).isEmpty();
        verify(userDetailsService, never()).loadByEmail(ArgumentMatchers.anyString(), ArgumentMatchers.anyBoolean());
    }

    @Test
    void rejectsWhenNotWhitelisted() {
        when(userDetailsService.loadByEmail(eq("ghost@corp.com"), eq(false)))
                .thenThrow(new UsernameNotFoundException("not found"));
        LarkIdentity id = new LarkIdentity(null, "ghost@corp.com", TENANT, "ou_1", true);
        assertThat(auth.authenticate(id)).isEmpty();
    }

    @Test
    void rejectsNullIdentity() {
        assertThat(auth.authenticate(null)).isEmpty();
    }
}
