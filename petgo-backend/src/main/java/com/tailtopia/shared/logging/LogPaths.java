package com.tailtopia.shared.logging;

import java.util.regex.Pattern;

/**
 * 请求路径脱敏（v1.4.2 shop Story 5.1 AC14，§1.2 第 25 条 / I14）。
 *
 * <p>Biteship Webhook 用<b>路径密钥</b>鉴权（{@code /api/v1/webhooks/biteship/{secret}}）：路径一旦原样进 access log、
 * WARN 日志或 ProblemDetail 的 {@code instance}，密钥就泄露了。所有打印 / 回显请求 URI 的地方都经这里。
 */
public final class LogPaths {

    private static final Pattern BITESHIP_WEBHOOK = Pattern.compile("^/api/v1/webhooks/biteship/[^/?]+");

    private LogPaths() {
    }

    /** {@code /api/v1/webhooks/biteship/abc} → {@code /api/v1/webhooks/biteship/***}；其余原样返回（含 null）。 */
    public static String redact(String uri) {
        if (uri == null || !uri.startsWith("/api/v1/webhooks/biteship/")) {
            return uri;
        }
        return BITESHIP_WEBHOOK.matcher(uri).replaceFirst("/api/v1/webhooks/biteship/***");
    }
}
