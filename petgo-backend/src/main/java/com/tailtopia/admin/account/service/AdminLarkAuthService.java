package com.tailtopia.admin.account.service;

import com.tailtopia.admin.account.dto.LarkIdentity;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminUserDetailsService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Lark 登录门控（Story 1.2 AC3/AC4）。把 Lark 身份转成后台登录主体或拒绝：
 * <ol>
 *   <li>企业租户校验：{@code tenant_key} 必须等于配置的本企业租户；</li>
 *   <li>邮箱已验证校验；</li>
 *   <li>白名单匹配：解析邮箱命中 {@code admin_accounts} 的 {@code ACTIVE} 账号（经 {@code loadByEmail(.., false)}）。</li>
 * </ol>
 * 任一不满足 → 返回空（拒绝，不建会话）。纯逻辑、可单测（mock service）。
 */
@Service
public class AdminLarkAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminLarkAuthService.class);

    private final AdminUserDetailsService adminUserDetailsService;
    private final String expectedTenantKey;

    public AdminLarkAuthService(AdminUserDetailsService adminUserDetailsService,
            @Value("${admin.lark.tenant-key:}") String expectedTenantKey) {
        this.adminUserDetailsService = adminUserDetailsService;
        this.expectedTenantKey = expectedTenantKey;
    }

    /** 通过校验则返回后台登录主体；否则空（拒绝）。 */
    public Optional<AdminUserDetails> authenticate(LarkIdentity id) {
        if (id == null) {
            return Optional.empty();
        }
        if (expectedTenantKey == null || expectedTenantKey.isBlank()
                || !expectedTenantKey.equals(id.tenantKey())) {
            log.warn("Lark 登录拒绝：租户不符 openId={}", id.openId());
            return Optional.empty();
        }
        if (!id.emailVerified()) {
            log.warn("Lark 登录拒绝：邮箱未验证 openId={}", id.openId());
            return Optional.empty();
        }
        String email = id.resolvedEmail();
        if (email == null || email.isBlank()) {
            log.warn("Lark 登录拒绝：无可用邮箱 openId={}", id.openId());
            return Optional.empty();
        }
        try {
            // OAuth 路径不要求密码（requirePassword=false）；status=ACTIVE 即白名单命中。
            return Optional.of(adminUserDetailsService.loadByEmail(email, false));
        } catch (UsernameNotFoundException e) {
            log.warn("Lark 登录拒绝：邮箱不在白名单或已停用 email={}", email);
            return Optional.empty();
        }
    }
}
