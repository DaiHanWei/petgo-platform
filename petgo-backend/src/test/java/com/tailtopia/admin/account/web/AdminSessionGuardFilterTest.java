package com.tailtopia.admin.account.web;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：会话守卫（AC5/A1）—— ACTIVE 放行；停用/不存在则失效会话并跳 ?expired；放行 login/oauth 防死循环。 */
class AdminSessionGuardFilterTest {

    private AdminAccountRepository repo;
    private AdminSessionGuardFilter filter;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        repo = mock(AdminAccountRepository.class);
        filter = new AdminSessionGuardFilter(repo);
        req = mock(HttpServletRequest.class);
        resp = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        when(req.getContextPath()).thenReturn("");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(long adminAccountId) {
        AdminUserDetails admin = new AdminUserDetails(adminAccountId, 99L, "ops@corp.com", null,
                AdminAccountType.SUPER_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    private AdminAccount account(AdminAccountStatus status) {
        AdminAccount a = AdminAccount.newSuperAdmin("ops@corp.com", "运营", "{bcrypt}h");
        ReflectionTestUtils.setField(a, "id", 7L);
        ReflectionTestUtils.setField(a, "status", status);
        return a;
    }

    @Test
    void skipsLoginPathWithoutDbCheck() throws Exception {
        when(req.getRequestURI()).thenReturn("/admin/login");
        filter.doFilterInternal(req, resp, chain);
        verify(chain).doFilter(req, resp);
        verifyNoInteractions(repo);
    }

    @Test
    void skipsOauthPathWithoutDbCheck() throws Exception {
        when(req.getRequestURI()).thenReturn("/admin/oauth/lark/callback");
        filter.doFilterInternal(req, resp, chain);
        verify(chain).doFilter(req, resp);
        verifyNoInteractions(repo);
    }

    @Test
    void passesWhenAccountActive() throws Exception {
        when(req.getRequestURI()).thenReturn("/admin/dashboard");
        authenticateAs(7L);
        when(repo.findById(7L)).thenReturn(Optional.of(account(AdminAccountStatus.ACTIVE)));
        filter.doFilterInternal(req, resp, chain);
        verify(chain).doFilter(req, resp);
        verify(resp, never()).sendRedirect(contains("expired"));
    }

    @Test
    void invalidatesSessionWhenAccountDisabled() throws Exception {
        when(req.getRequestURI()).thenReturn("/admin/dashboard");
        authenticateAs(7L);
        when(repo.findById(7L)).thenReturn(Optional.of(account(AdminAccountStatus.DISABLED)));
        HttpSession session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);

        filter.doFilterInternal(req, resp, chain);

        verify(session).invalidate();
        verify(resp).sendRedirect(contains("/admin/login?expired"));
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    void invalidatesSessionWhenAccountAbsent() throws Exception {
        when(req.getRequestURI()).thenReturn("/admin/vets");
        authenticateAs(8L);
        when(repo.findById(8L)).thenReturn(Optional.empty());
        when(req.getSession(false)).thenReturn(null);

        filter.doFilterInternal(req, resp, chain);

        verify(resp).sendRedirect(contains("/admin/login?expired"));
        verify(chain, never()).doFilter(req, resp);
    }
}
