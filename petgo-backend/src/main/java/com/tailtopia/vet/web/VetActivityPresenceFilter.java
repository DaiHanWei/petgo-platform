package com.tailtopia.vet.web;

import com.tailtopia.vet.service.VetPresenceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 兽医活动续期（在线态兜底）：兽医在用工作台（任意 {@code /api/v1/vet/**} 请求）且<b>当前在线</b>时，
 * 凭该请求续期在线 TTL —— 防「工作台前台轮询中但客户端心跳缺失 → 误判离线」（实证见
 * {@code ApiAccessLoggingFilter}：兽医狂轮询 waiting 却 0 心跳，3min 后掉线）。
 *
 * <p>边界：① 仅 {@code role=VET}；② {@link VetPresenceService#refreshIfOnline} 只续期、<b>不复活离线兽医</b>；
 * ③ 排除在线态管理端点（online-status/heartbeat/logout）以免干扰显式上下线；④ 续期放在链后执行，
 * 避免与同请求内的 {@code goOffline}（离线切换）相互覆盖。默认 @Component 顺序 → 在 Spring Security 之后，
 * 此时 {@link SecurityContextHolder} 已就绪。
 */
@Component
public class VetActivityPresenceFilter extends OncePerRequestFilter {

    private final VetPresenceService presence;

    public VetActivityPresenceFilter(VetPresenceService presence) {
        this.presence = presence;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        if (!p.startsWith("/api/v1/vet/")) {
            return true;
        }
        // 在线态管理端点由各自逻辑显式处理，活动续期不掺和。
        return p.endsWith("/online-status") || p.endsWith("/heartbeat") || p.endsWith("/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            Long vetId = currentVetId();
            if (vetId != null) {
                try {
                    presence.refreshIfOnline(vetId);
                } catch (Exception ignored) {
                    // 续期失败绝不可影响业务响应。
                }
            }
        }
    }

    private Long currentVetId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        boolean isVet = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_VET".equals(a.getAuthority()));
        if (!isVet) {
            return null;
        }
        try {
            return Long.parseLong(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
