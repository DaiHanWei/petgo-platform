package com.petgo.vet.web;

import com.petgo.vet.service.VetAccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 封禁即生效拦截（Story 5.7，AC1）。已登录兽医每次请求校验账号 status——
 * BANNED（或账号不存在）→ <b>立即 401</b>，不依赖 JWT 自然过期（保证封禁立即踢下线）。
 *
 * <p>仅对 {@code role=VET} 的已认证请求查 status（低频，DB 命中可接受）；其余请求快速放行。
 * 输出 RFC 9457 ProblemDetail（401，前端据此清登录态、踢回登录页）。
 */
@Component
public class BannedVetFilter extends OncePerRequestFilter {

    private final VetAccountService vetAccounts;

    public BannedVetFilter(VetAccountService vetAccounts) {
        this.vetAccounts = vetAccounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && isVet(jwtAuth)) {
            Long vetId = parseVetId(jwtAuth.getName());
            if (vetId == null || !vetAccounts.isActive(vetId)) {
                writeUnauthorized(response, request.getRequestURI());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isVet(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_VET".equals(a.getAuthority()));
    }

    private static Long parseVetId(String subject) {
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void writeUnauthorized(HttpServletResponse resp, String uri) throws IOException {
        resp.setStatus(HttpStatus.UNAUTHORIZED.value());
        resp.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String instance = uri == null ? "" : uri.replace("\\", "\\\\").replace("\"", "\\\"");
        resp.getWriter().write("{"
                + "\"type\":\"https://petgo/errors/account-disabled\","
                + "\"title\":\"Unauthorized\","
                + "\"status\":401,"
                + "\"detail\":\"账号已被停用，请联系运营\","
                + "\"instance\":\"" + instance + "\""
                + "}");
    }
}
