package com.tailtopia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 🔒 L0 · V1.4.2 social-topic Story 4.2 · AC9（G-4）：联想仅 role=USER；话题读接口 GET 游客可看（扫源码，范式同
 * {@code CollectEndpointsRoleGuardTest}）。401 / 403 的真实行为在 L1 {@code TopicApiIntegrationTest}。
 */
class TopicEndpointsSecurityGuardTest {

    private static final Path SECURITY_CONFIG =
            Path.of("src", "main", "java", "com", "tailtopia", "shared", "security", "SecurityConfig.java");

    @Test
    void suggestionsUserOnlyAndTopicsGetPublic() throws IOException {
        String src = Files.readString(SECURITY_CONFIG, StandardCharsets.UTF_8);
        String suggest = ".requestMatchers(HttpMethod.GET, \"/api/v1/topic-suggestions\").hasRole(\"USER\")";
        String topics = ".requestMatchers(HttpMethod.GET, \"/api/v1/topics/**\").permitAll()";
        assertThat(src).contains(suggest).contains(topics);
        // 插在 GET /content-posts/** permitAll 之后、mini-profile 之前（AC9）
        int contentPosts = src.indexOf("HttpMethod.GET, \"/api/v1/content-posts/**\"");
        int miniProfile = src.indexOf("\"/api/v1/users/*/mini-profile\"");
        assertThat(src.indexOf(suggest)).isGreaterThan(contentPosts).isLessThan(miniProfile);
        assertThat(src.indexOf(topics)).isGreaterThan(contentPosts).isLessThan(miniProfile);
        // 只放 GET：不得出现不带方法的 /api/v1/topics/** 放行
        assertThat(src).doesNotContain(".requestMatchers(\"/api/v1/topics/**\")");
    }
}
