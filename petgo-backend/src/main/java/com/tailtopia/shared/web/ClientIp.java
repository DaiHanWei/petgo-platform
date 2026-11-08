package com.tailtopia.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取用于限流的客户端 IP（v1.4.2 ocr 审查 M2）。
 *
 * <p>🔴 不能取 {@code X-Forwarded-For} 的<b>首段</b>：Cloudflare 会保留客户端自带的 XFF、只在末尾追加真实 IP，
 * 首段由请求方任意伪造，按它限流等于每次换个头就绕过。
 *
 * <p>取值顺序：
 * <ol>
 *   <li>{@code CF-Connecting-IP} —— Cloudflare 写入并覆盖，客户端无法伪造（公网入口只有 CF 隧道，源站端口绑 127.0.0.1）；</li>
 *   <li>{@code X-Forwarded-For} 的<b>末段</b> —— 最近一跳可信代理追加的地址；</li>
 *   <li>{@code remoteAddr}。</li>
 * </ol>
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest http) {
        String cf = http.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String[] parts = fwd.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return http.getRemoteAddr();
    }
}
