package com.tailtopia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * L0：v1.4.2 shop Story 3.1 AC7 —— {@code /api/v1/me/checkout/**} 在电商 USER 精确表里（读源码断言，
 * 照 {@code SecurityConfigCouponRulesTest}）。不加则 {@code POST /me/checkout/preview} 落到
 * {@code anyRequest().authenticated()}，兽医 token 可以同号用户名义试算。
 */
class CheckoutPreviewSecurityGuardTest {

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/com/tailtopia/shared/security/SecurityConfig.java"),
                StandardCharsets.UTF_8);
    }

    @Test
    void checkoutSubPathsAreUserOnly() throws IOException {
        Matcher m = Pattern.compile("\\.requestMatchers\\(([^;]*?\"/api/v1/me/shop/\\*\\*\")\\)\\.hasRole\\(\"USER\"\\)",
                Pattern.DOTALL).matcher(source());
        assertThat(m.find()).as("电商 /me/* hasRole USER 规则存在").isTrue();
        assertThat(m.group(1)).contains("\"/api/v1/me/checkout\"").contains("\"/api/v1/me/checkout/**\"");
    }

    @Test
    void ruleIsBeforeFallback() throws IOException {
        String s = source();
        assertThat(s.indexOf("\"/api/v1/me/checkout/**\""))
                .isPositive().isLessThan(s.lastIndexOf(".anyRequest().authenticated()"));
    }
}
