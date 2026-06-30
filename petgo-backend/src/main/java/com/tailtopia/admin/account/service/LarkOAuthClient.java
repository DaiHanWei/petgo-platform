package com.tailtopia.admin.account.service;

import com.tailtopia.admin.account.dto.LarkIdentity;
import com.tailtopia.shared.error.AppException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Lark（国际版 open.larksuite.com）OAuth 授权码流客户端（Story 1.2）。
 *
 * <p><b>非标准 OIDC</b>：先取 app_access_token，再以它换 user_access_token 及内嵌用户身份；故手写流（RestClient），
 * 不用 spring-boot-starter-oauth2-client。凭证 env 注入绝不入库；token/code 绝不入日志。
 *
 * <p>端点（base-url 可配以区分环境）：
 * <ul>
 *   <li>app_access_token：{@code POST /open-apis/auth/v3/app_access_token/internal}</li>
 *   <li>code→身份：{@code POST /open-apis/authen/v1/oidc/access_token}（Header 带 app_access_token）</li>
 *   <li>授权页：{@code GET /open-apis/authen/v1/authorize}</li>
 * </ul>
 * 注：响应字段以 Lark 文档为准，L2 真实凭证验收时核对（见 story Dev Notes）。
 */
@Component
public class LarkOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(LarkOAuthClient.class);

    private final String appId;
    private final String appSecret;
    private final String redirectUri;
    private final RestClient rest;

    public LarkOAuthClient(
            @Value("${admin.lark.app-id:}") String appId,
            @Value("${admin.lark.app-secret:}") String appSecret,
            @Value("${admin.lark.redirect-uri:}") String redirectUri,
            @Value("${admin.lark.base-url:https://open.larksuite.com}") String baseUrl) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.redirectUri = redirectUri;
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 拼授权页 URL（带 state CSRF）。 */
    public String authorizeUrl(String state) {
        String enc = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        return "/open-apis/authen/v1/authorize?app_id=" + appId
                + "&redirect_uri=" + enc + "&response_type=code&state=" + state;
    }

    /** 用 code 换用户身份（app_access_token → user_access_token + 身份）。失败抛 {@link AppException}。 */
    public LarkIdentity exchangeCode(String code) {
        String appAccessToken = fetchAppAccessToken();
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = rest.post()
                .uri("/open-apis/authen/v1/oidc/access_token")
                .header("Authorization", "Bearer " + appAccessToken)
                .body(Map.of("grant_type", "authorization_code", "code", code))
                .retrieve()
                .body(Map.class);
        return mapIdentity(resp);
    }

    private String fetchAppAccessToken() {
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = rest.post()
                .uri("/open-apis/auth/v3/app_access_token/internal")
                .body(Map.of("app_id", appId, "app_secret", appSecret))
                .retrieve()
                .body(Map.class);
        Object token = resp == null ? null : resp.get("app_access_token");
        if (token == null) {
            log.warn("Lark app_access_token 获取失败 code={}", resp == null ? null : resp.get("code"));
            throw AppException.validation("Lark 鉴权失败");
        }
        return token.toString();
    }

    /** 将 Lark 响应映射为 {@link LarkIdentity}（容错 data 包裹层）。包级可见以便单测。 */
    @SuppressWarnings("unchecked")
    LarkIdentity mapIdentity(Map<String, Object> resp) {
        if (resp == null) {
            throw AppException.validation("Lark 登录失败");
        }
        Map<String, Object> data = resp.get("data") instanceof Map
                ? (Map<String, Object>) resp.get("data") : resp;
        String email = asStr(data.get("email"));
        String enterpriseEmail = asStr(data.get("enterprise_email"));
        String tenantKey = asStr(data.get("tenant_key"));
        String openId = asStr(data.get("open_id"));
        // 企业邮箱存在即视为租户内已验证；无企业邮箱时按 Lark 返回的 email_verified（缺省 false）。
        boolean verified = (enterpriseEmail != null && !enterpriseEmail.isBlank())
                || Boolean.TRUE.equals(data.get("email_verified"));
        return new LarkIdentity(email, enterpriseEmail, tenantKey, openId, verified);
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
    }
}
