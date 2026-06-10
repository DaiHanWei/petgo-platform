package com.tailtopia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.mockito.Mockito;

/**
 * L0 单元测试（无 DB/容器）：refresh 明文生成不可枚举、hash 确定性、过期推算。
 */
class JwtServiceTest {

    private JwtService service() {
        JwtEncoder encoder = Mockito.mock(JwtEncoder.class);
        return new JwtService(encoder, new AuthProperties());
    }

    @Test
    void hashIsDeterministicAndDiffersByInput() {
        JwtService svc = service();
        assertThat(svc.hashRefresh("abc")).isEqualTo(svc.hashRefresh("abc"));
        assertThat(svc.hashRefresh("abc")).isNotEqualTo(svc.hashRefresh("xyz"));
        // SHA-256 hex 长度 64
        assertThat(svc.hashRefresh("abc")).hasSize(64);
    }

    @Test
    void generatedRefreshRawIsUnique() {
        JwtService svc = service();
        assertThat(svc.generateRefreshRaw()).isNotEqualTo(svc.generateRefreshRaw());
    }

    @Test
    void refreshExpiryAddsConfiguredDays() {
        JwtService svc = service();
        Instant now = Instant.now();
        // 默认 30 天
        assertThat(svc.refreshExpiry(now)).isAfter(now.plusSeconds(29L * 86400));
    }
}
