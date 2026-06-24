package com.tailtopia.shared.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 全接口访问日志（req + resp）。日志专用 logger {@code ApiAccessLog} → 落 ECS JSON 文件（按日期拆分）。
 *
 * <p>护栏（CLAUDE.md）：body 经 {@link LogSanitizer} 脱敏（令牌/PII/健康/签名URL 打码）；
 * <b>绝不记录 Authorization 头原值</b> —— 角色仅从 JWT payload <i>不验签</i>解码出 {@code sub/role} 供排查。
 *
 * <p>置于 {@code HIGHEST_PRECEDENCE} 包在 Spring Security 外层：连被 security 拒绝的 401/403 也能记录
 * （这正是排查鉴权问题最需要的）。仅记 {@code /api/**} 与 {@code /im/callback}，跳过 actuator/swagger/docs。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiAccessLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("com.tailtopia.shared.logging.ApiAccessLog");

    /** 请求体缓存上限（64KB）：超出部分不缓存，防大体积请求撑内存（JSON 请求体远小于此）。 */
    private static final int REQ_CACHE_LIMIT = 64 * 1024;

    private final LogSanitizer sanitizer;
    // 自建 ObjectMapper：仅用于不验签解码 JWT payload（Boot 4 无 Jackson 2 ObjectMapper bean）。
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiAccessLoggingFilter(LogSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        boolean logged = p.startsWith("/api/") || p.equals("/im/callback");
        return !logged;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        boolean cacheReq = hasJsonBody(request);
        HttpServletRequest req =
                cacheReq ? new ContentCachingRequestWrapper(request, REQ_CACHE_LIMIT) : request;
        ContentCachingResponseWrapper resp = new ContentCachingResponseWrapper(response);

        long start = System.nanoTime();
        try {
            chain.doFilter(req, resp);
        } finally {
            long durMs = (System.nanoTime() - start) / 1_000_000;
            try {
                logExchange(request, req, resp, durMs);
            } catch (Exception e) {
                // 日志本身绝不可影响业务响应。
                log.warn("api access log failed: {}", e.toString());
            }
            resp.copyBodyToResponse(); // 必须：把缓存的响应体写回真实输出流
        }
    }

    private void logExchange(HttpServletRequest original, HttpServletRequest req,
            ContentCachingResponseWrapper resp, long durMs) {
        String reqBody = "";
        if (req instanceof ContentCachingRequestWrapper w) {
            reqBody = sanitizer.sanitize(w.getContentAsByteArray(), original.getContentType());
        } else if (original.getContentLength() > 0) {
            reqBody = "<" + safe(original.getContentType()) + ", " + original.getContentLength() + "B>";
        }
        String respBody = sanitizer.sanitize(resp.getContentAsByteArray(), resp.getContentType());

        String[] subRole = subAndRole(original.getHeader("Authorization"));
        String query = original.getQueryString();

        log.info("api method={} path={}{} status={} durMs={} sub={} role={} req={} resp={}",
                original.getMethod(),
                original.getRequestURI(),
                query == null ? "" : "?" + query,
                resp.getStatus(),
                durMs,
                subRole[0],
                subRole[1],
                reqBody,
                respBody);
    }

    private boolean hasJsonBody(HttpServletRequest request) {
        String ct = request.getContentType();
        return ct != null && ct.toLowerCase().contains("json");
    }

    /** 从 Bearer JWT payload 不验签解码 {@code sub}/{@code role}（仅日志用，绝不据此鉴权）。 */
    private String[] subAndRole(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return new String[] {"-", "guest"};
        }
        try {
            String[] parts = authHeader.substring(7).split("\\.");
            if (parts.length < 2) {
                return new String[] {"-", "?"};
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = mapper.readTree(payload);
            String sub = node.path("sub").asText("-");
            String role = node.path("role").asText("?");
            return new String[] {sub, role};
        } catch (Exception e) {
            return new String[] {"-", "?"};
        }
    }

    private String safe(String ct) {
        return ct == null ? "binary" : ct;
    }
}
