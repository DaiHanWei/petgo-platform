package com.petgo.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 安全层 401/403 统一输出 RFC 9457 ProblemDetail（与 {@code shared/error} 一致信封）。
 *
 * <p>401 未认证（→ 前端走 FR-0C 登录引导）与 403 越权（→ 友好提示）严格区分不混用。
 * 直接写 {@code application/problem+json}，避免依赖 Jackson 版本差异。绝不外泄堆栈。
 */
@Component
public class ProblemDetailAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "需要登录后访问", request);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "没有权限访问该资源", request);
    }

    private void write(HttpServletResponse resp, HttpStatus status, String typeSlug, String title,
            String detail, HttpServletRequest req) throws IOException {
        resp.setStatus(status.value());
        resp.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String instance = escape(req.getRequestURI());
        String json = "{"
                + "\"type\":\"https://petgo/errors/" + typeSlug + "\","
                + "\"title\":\"" + title + "\","
                + "\"status\":" + status.value() + ","
                + "\"detail\":\"" + detail + "\","
                + "\"instance\":\"" + instance + "\""
                + "}";
        resp.getWriter().write(json);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
