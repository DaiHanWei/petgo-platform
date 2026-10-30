package com.tailtopia.merchant.po.internal;

import com.tailtopia.merchant.po.service.MerchantApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部接口的来源与密钥守卫（契约 5.1）。在 Spring Security 之前执行。
 *
 * <ol>
 *   <li>端口分区：{@code /internal/**} 只认独立端口，主端口上 → 404；独立端口上的其它路径 → 404。</li>
 *   <li>带 {@code CF-Connecting-IP} 的请求 → 403 {@code forbidden}（经 Cloudflare 进来的一律不收；附加防线，不是唯一判据）。</li>
 *   <li>{@code X-Internal-Secret} 常量时间比较，不符或未配置 → 401 {@code unauthorized}。</li>
 * </ol>
 *
 * <p>🔒 密钥、门店身份头都不进日志（本类不记任何请求内容）。
 */
public class InternalApiGuardFilter extends OncePerRequestFilter {

    static final String PREFIX = "/internal/";

    private final MerchantInternalProperties props;

    public InternalApiGuardFilter(MerchantInternalProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean internalPath = path.startsWith(PREFIX) || path.equals("/internal");
        boolean onInternalPort = props.enabled() && request.getLocalPort() == props.getPort();

        if (onInternalPort != internalPath) {
            // 主端口访问 /internal/**，或内部端口访问别的路径：都当不存在。
            write(response, 404, MerchantApiException.NOT_FOUND);
            return;
        }
        if (!internalPath) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getHeader("CF-Connecting-IP") != null) {
            write(response, 403, MerchantApiException.FORBIDDEN);
            return;
        }
        if (!secretMatches(request.getHeader("X-Internal-Secret"))) {
            write(response, 401, MerchantApiException.UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean secretMatches(String presented) {
        String expected = props.getSecret();
        if (expected == null || expected.isEmpty() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(HttpServletResponse response, int status, String type) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(InternalProblems.json(status, type));
    }
}
