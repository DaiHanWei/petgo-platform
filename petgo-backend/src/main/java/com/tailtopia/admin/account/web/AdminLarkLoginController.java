package com.tailtopia.admin.account.web;

import com.tailtopia.admin.account.dto.LarkIdentity;
import com.tailtopia.admin.account.service.AdminLarkAuthService;
import com.tailtopia.admin.account.service.LarkOAuthClient;
import com.tailtopia.admin.service.AdminUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Lark OAuth 登录编排（Story 1.2 AC1/AC2/AC4）。手写授权码流：
 * {@code /admin/oauth/lark/login}（生成 state→跳授权页）→ {@code /admin/oauth/lark/callback}
 * （校验 state→换身份→门控→建服务端会话或拒绝）。
 *
 * <p>会话用 {@link HttpSessionSecurityContextRepository} 持久化，与 formLogin 同机制，
 * 后续请求经 adminFilterChain 识别为已认证（principal = {@link AdminUserDetails}）。
 */
@Controller
public class AdminLarkLoginController {

    private static final Logger log = LoggerFactory.getLogger(AdminLarkLoginController.class);
    private static final String STATE_ATTR = "LARK_OAUTH_STATE";

    private final LarkOAuthClient larkClient;
    private final AdminLarkAuthService larkAuth;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AdminLarkLoginController(LarkOAuthClient larkClient, AdminLarkAuthService larkAuth) {
        this.larkClient = larkClient;
        this.larkAuth = larkAuth;
    }

    /** 主入口：生成一次性 state 存 session，跳 Lark 授权页。 */
    @GetMapping("/admin/oauth/lark/login")
    public String login(HttpSession session) {
        String state = UUID.randomUUID().toString().replace("-", "");
        session.setAttribute(STATE_ATTR, state);
        return "redirect:" + larkClient.authorizeUrl(state);
    }

    /** 回调：校验 state（一次性）→ 换身份 → 门控 → 建会话或拒绝。 */
    @GetMapping("/admin/oauth/lark/callback")
    public String callback(@RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        Object expected = session == null ? null : session.getAttribute(STATE_ATTR);
        if (session != null) {
            session.removeAttribute(STATE_ATTR); // 一次性消费，防重放
        }
        if (code == null || state == null || expected == null || !expected.equals(state)) {
            log.warn("Lark 回调拒绝：state 校验失败");
            return "redirect:/admin/login?denied";
        }

        LarkIdentity identity;
        try {
            identity = larkClient.exchangeCode(code);
        } catch (RuntimeException e) {
            log.warn("Lark 回调拒绝：换取身份失败");
            return "redirect:/admin/login?denied";
        }

        Optional<AdminUserDetails> principal = larkAuth.authenticate(identity);
        if (principal.isEmpty()) {
            return "redirect:/admin/login?denied";
        }

        // 建立服务端会话（与 formLogin 同机制）。
        AdminUserDetails admin = principal.get();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        log.info("Lark 登录成功 adminAccountId={}", admin.getAdminAccountId());
        return "redirect:/admin/dashboard";
    }
}
