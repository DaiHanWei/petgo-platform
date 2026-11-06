package com.tailtopia.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 5.1 AC14 —— Biteship Webhook 路径密钥不进日志 / ProblemDetail。 */
class ApiAccessLogPathRedactionTest {

    @Test
    @DisplayName("Webhook 路径密钥打码为 ***")
    void webhookSecretRedacted() {
        assertThat(ApiAccessLoggingFilter.redactPath("/api/v1/webhooks/biteship/abc123"))
                .isEqualTo("/api/v1/webhooks/biteship/***");
        assertThat(LogPaths.redact("/api/v1/webhooks/biteship/s3cr3t/extra"))
                .isEqualTo("/api/v1/webhooks/biteship/***/extra").doesNotContain("s3cr3t");
    }

    @Test
    @DisplayName("带 query 的也打码")
    void withQuery() {
        assertThat(LogPaths.redact("/api/v1/webhooks/biteship/abc123?x=1"))
                .isEqualTo("/api/v1/webhooks/biteship/***?x=1");
    }

    @Test
    @DisplayName("其他路径原样；null 原样")
    void otherPathsUntouched() {
        assertThat(LogPaths.redact("/api/v1/me/shop/orders/abc")).isEqualTo("/api/v1/me/shop/orders/abc");
        assertThat(LogPaths.redact("/api/v1/webhooks/biteship/")).isEqualTo("/api/v1/webhooks/biteship/");
        assertThat(LogPaths.redact(null)).isNull();
    }
}
