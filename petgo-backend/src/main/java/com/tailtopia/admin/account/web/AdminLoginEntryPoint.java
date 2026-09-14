package com.tailtopia.admin.account.web;

import com.tailtopia.admin.shared.web.HxRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * admin 链未认证入口（会话自然过期 / 未登录访问）：htmx 请求回 {@code HX-Redirect} 整页跳登录页，
 * 其余请求一律照旧 302 到 {@code /admin/login}（与 formLogin 自带入口同一行为）。
 *
 * <p>🔴 必须以 {@code exceptionHandling().authenticationEntryPoint(...)} <b>显式</b>装配，不能用
 * {@code defaultAuthenticationEntryPointFor(htmx 入口, HX-Request 匹配器)} 追加：Spring Security 在
 * 入口表多于一条且未显式指定默认时，会把<b>第一条</b>当兜底 —— 先注册的 htmx 入口于是接管了所有
 * 两个匹配器都不命中的请求（{@code Accept: *}{@code /*} 的 fetch 上传），过期后回 200 + HX-Redirect 而非 302，
 * 前端靠 {@code r.redirected} 认会话过期的逻辑随之失效（复审 0914 P0）。
 */
public class AdminLoginEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint loginPage = new LoginUrlAuthenticationEntryPoint("/admin/login");

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException, jakarta.servlet.ServletException {
        if (HxRequest.of(request).isHtmx()) {
            AdminSessionGuardFilter.redirectToLogin(request, response, "expired");
            return;
        }
        loginPage.commence(request, response, authException);
    }
}
