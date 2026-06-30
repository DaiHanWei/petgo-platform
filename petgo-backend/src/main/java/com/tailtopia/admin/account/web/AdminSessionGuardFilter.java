package com.tailtopia.admin.account.web;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 后台会话守卫（Story 1.2 AC5，护栏 A1）。对**每次**已认证后台请求，按 principal 的
 * {@code adminAccountId} 重查 {@code admin_accounts}；账号不存在或 {@code status!=ACTIVE}
 * （被移出白名单/停用）→ 立即失效会话并跳登录页（{@code ?expired}）。实现「撤权即时生效」。
 *
 * <p>放行 {@code /admin/login} 与 {@code /admin/oauth/**} 自身，避免未登录/建会话过程被拦成死循环。
 * 非 {@code @Component}（避免注册到全局链），由 {@code SecurityConfig} 仅装配进 admin 链。
 */
public class AdminSessionGuardFilter extends OncePerRequestFilter {

    private final AdminAccountRepository adminAccounts;

    public AdminSessionGuardFilter(AdminAccountRepository adminAccounts) {
        this.adminAccounts = adminAccounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if ("/admin/login".equals(uri) || uri.startsWith("/admin/oauth/")) {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof AdminUserDetails admin) {
            Optional<AdminAccount> current = adminAccounts.findById(admin.getAdminAccountId());
            boolean active = current.map(a -> a.getStatus() == AdminAccountStatus.ACTIVE).orElse(false);
            if (!active) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
                response.sendRedirect(request.getContextPath() + "/admin/login?expired");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
