package com.tailtopia.admin.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.account.dto.LarkIdentity;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0：Lark 客户端的纯逻辑（授权 URL 拼装 + 身份映射）。HTTP 调用属 L2，真实凭证验收。 */
class LarkOAuthClientTest {

    private LarkOAuthClient client() {
        return new LarkOAuthClient("appid-1", "secret-1",
                "https://admin.tailtopia.id/admin/oauth/lark/callback", "https://open.larksuite.com");
    }

    @Test
    void authorizeUrlContainsAppIdStateAndEncodedRedirect() {
        String url = client().authorizeUrl("st8");
        // 必须是绝对地址(带 base-url)——浏览器 redirect 跳 Lark 授权页，相对路径会被解析成本站 host 而打不到 Lark。
        assertThat(url).startsWith("https://open.larksuite.com/open-apis/authen/v1/authorize?");
        assertThat(url).contains("app_id=appid-1");
        assertThat(url).contains("state=st8");
        assertThat(url).contains("response_type=code");
        // redirect_uri 经 URL 编码
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fadmin.tailtopia.id%2Fadmin%2Foauth%2Flark%2Fcallback");
    }

    @Test
    void mapsIdentityFromDataWrapperPreferringEnterpriseEmail() {
        LarkIdentity id = client().mapIdentity(Map.of("data", Map.of(
                "email", "personal@x.com",
                "enterprise_email", "ops@corp.com",
                "tenant_key", "t1",
                "open_id", "ou_1")));
        assertThat(id.enterpriseEmail()).isEqualTo("ops@corp.com");
        assertThat(id.resolvedEmail()).isEqualTo("ops@corp.com"); // 企业邮箱优先
        assertThat(id.tenantKey()).isEqualTo("t1");
        assertThat(id.emailVerified()).isTrue(); // 有企业邮箱 → 视为已验证
    }

    @Test
    void mapsIdentityFlatWithEmailVerifiedFlag() {
        LarkIdentity id = client().mapIdentity(Map.of(
                "email", "personal@x.com",
                "tenant_key", "t1",
                "open_id", "ou_2",
                "email_verified", true));
        assertThat(id.resolvedEmail()).isEqualTo("personal@x.com");
        assertThat(id.emailVerified()).isTrue();
    }

    @Test
    void unverifiedWhenNoEnterpriseEmailAndNoFlag() {
        LarkIdentity id = client().mapIdentity(Map.of(
                "email", "personal@x.com", "tenant_key", "t1", "open_id", "ou_3"));
        assertThat(id.emailVerified()).isFalse();
    }
}
