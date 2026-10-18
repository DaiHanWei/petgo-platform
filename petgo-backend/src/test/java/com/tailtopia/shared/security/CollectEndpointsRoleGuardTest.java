package com.tailtopia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 🔒 L0：V1.4.0 ④ Story 2.1 · AC7 —— 收藏写端点显式 {@code hasRole("USER")}（扫源码，范式同
 * {@code ShopUserEndpointsRoleGuardTest}）。漏了会落到 {@code anyRequest().authenticated()}，兽医 token
 * （sub = vetId）能以同号用户的名义收藏 / 取消。
 */
class CollectEndpointsRoleGuardTest {

    private static final Path SECURITY_CONFIG =
            Path.of("src", "main", "java", "com", "tailtopia", "shared", "security", "SecurityConfig.java");

    @Test
    void collectEndpointsRequireUserRole() throws IOException {
        String src = Files.readString(SECURITY_CONFIG, StandardCharsets.UTF_8);
        String post = ".requestMatchers(\"/api/v1/content-posts/*/collect\").hasRole(\"USER\")";
        String place = ".requestMatchers(\"/api/v1/places/*/collect\").hasRole(\"USER\")";
        assertThat(src).contains(post).contains(place);
        // 必须排在 GET /api/v1/content-posts/** permitAll 之前（否则先匹配者胜，虽限 GET 也保持先后约定）
        assertThat(src.indexOf(post)).isLessThan(src.indexOf("HttpMethod.GET, \"/api/v1/content-posts/**\""));
        assertThat(src.indexOf(place)).isLessThan(src.indexOf(".anyRequest().authenticated()"));
    }
}
