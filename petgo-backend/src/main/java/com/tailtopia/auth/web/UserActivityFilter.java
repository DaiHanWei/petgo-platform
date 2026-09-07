package com.tailtopia.auth.web;

import com.tailtopia.auth.service.AccountQueryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 用户「最后活跃」刷新（留存运营作战手册 · 抓手 1 的流失判定依据）。
 *
 * <p>任意已认证的 {@code /api/v1} 请求都算一次活跃，每日至多落一次写
 * （条件 UPDATE 幂等，见 {@code UserRepository#touchLastActiveAt}）。
 * 用户侧此前<b>完全没有</b>活跃信号 —— 兽医侧有 Redis presence，用户侧没有对应物 ——
 * 没有它，「7 天未回」就只能拿注册天数瞎猜，召回会同时打扰天天在用的人。
 *
 * <h2>边界</h2>
 * <ol>
 *   <li><b>仅 {@code ROLE_USER}</b>：兽医 token 的 {@code sub=vetId}，与 {@code users.id} 是
 *       独立命名空间<b>且大量碰撞</b>（SecurityConfig 安全评审三轮 #1）。不加这道判断，
 *       兽医每次刷工作台都会把某个无关用户标记成「今天来过」，召回名单直接失真。</li>
 *   <li><b>放在链后、异常全吞</b>：活跃刷新失败最多让召回晚一轮；让用户的请求 500 是真事故。</li>
 * </ol>
 */
@Component
public class UserActivityFilter extends OncePerRequestFilter {

    private final AccountQueryService accounts;

    public UserActivityFilter(AccountQueryService accounts) {
        this.accounts = accounts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只关心用户端业务请求；健康检查/后台/静态资源不算活跃。
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            Long userId = currentUserId();
            if (userId != null) {
                try {
                    accounts.touchLastActive(userId, Instant.now());
                } catch (Exception ignored) {
                    // 刷新失败绝不可影响业务响应。
                }
            }
        }
    }

    /** 当前登录的<b>普通用户</b> id；兽医/管理员/未登录一律返回 null。 */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        boolean isUser = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_USER".equals(a.getAuthority()));
        if (!isUser) {
            return null;
        }
        try {
            return Long.parseLong(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
