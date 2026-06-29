package com.tailtopia.admin.account.web;

import com.tailtopia.admin.account.service.AdminLoginThrottle;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 后台登录限流前置过滤器（Story 1.1 AC4）。仅拦截 {@code POST /admin/login}：
 * 若该 username 已被锁定，直接重定向回登录页（{@code ?locked}），不进入认证（真正锁定，而非仅计数）。
 *
 * <p>非 {@code @Component}（避免被注册到全局链）；由 {@code SecurityConfig} 仅装配进 admin 链。
 */
public class AdminLoginThrottleFilter extends OncePerRequestFilter {

    private final AdminLoginThrottle throttle;

    public AdminLoginThrottleFilter(AdminLoginThrottle throttle) {
        this.throttle = throttle;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod())
                && "/admin/login".equals(request.getRequestURI())) {
            String username = request.getParameter("username");
            if (throttle.isLocked(username)) {
                response.sendRedirect(request.getContextPath() + "/admin/login?locked");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
