package com.tailtopia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 2.2 AC10 —— 券相关安全规则文本断言。 */
class SecurityConfigCouponRulesTest {

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/com/tailtopia/shared/security/SecurityConfig.java"),
                StandardCharsets.UTF_8);
    }

    @Test
    void newbieCampaignIsPermitAll() throws IOException {
        assertThat(source()).contains(
                ".requestMatchers(HttpMethod.GET, \"/api/v1/shop/coupons/newbie-campaign\").permitAll()");
    }

    @Test
    void forYouIsUserOnly() throws IOException {
        assertThat(source()).contains(
                ".requestMatchers(HttpMethod.GET, \"/api/v1/shop/coupons/for-you\").hasRole(\"USER\")");
    }

    @Test
    void noShopCouponsWildcard() throws IOException {
        assertThat(source()).doesNotContain("\"/api/v1/shop/coupons/**\"");
    }

    @Test
    void meCouponsInShopUserRuleList() throws IOException {
        // 取包含 "/api/v1/me/shop/**" 的那条 requestMatchers(...).hasRole("USER") 语句
        Matcher m = Pattern.compile("\\.requestMatchers\\(([^;]*?\"/api/v1/me/shop/\\*\\*\")\\)\\.hasRole\\(\"USER\"\\)",
                Pattern.DOTALL).matcher(source());
        assertThat(m.find()).as("电商 /me/* hasRole USER 规则存在").isTrue();
        String list = m.group(1);
        assertThat(list).contains("\"/api/v1/me/coupons\"").contains("\"/api/v1/me/coupons/**\"");
    }

    @Test
    void couponRulesPrecedeFallback() throws IOException {
        String s = source();
        int fallback = s.lastIndexOf(".anyRequest().authenticated()");
        assertThat(s.indexOf("\"/api/v1/me/coupons\"")).isPositive().isLessThan(fallback);
        assertThat(s.indexOf("/api/v1/shop/coupons/for-you")).isPositive().isLessThan(fallback);
    }
}
