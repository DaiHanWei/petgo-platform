package com.tailtopia.shared.web;

import java.util.Locale;

/**
 * 客户端能力声明（专题线 video Story 2.2 · AD-7 · 决策 V-36）。
 *
 * <p>新版 App 每个请求带 {@code X-App-Capabilities: video}（逗号分隔的小写能力名）；没有声明 = 旧版客户端。
 * 不比较版本号，所以不受 stag 包版本后缀影响。
 *
 * <p>🔴 <b>一致性规则 2：只在 Controller 层解析</b>，得到 {@code includeVideo} 后作为显式参数往下传；
 * 服务层不注入请求级 bean（服务可能在请求线程之外被调用）。未知能力值忽略。
 */
public final class ClientCapabilities {

    /** 请求头名。 */
    public static final String HEADER = "X-App-Capabilities";

    /** 能显示视频帖。 */
    public static final String VIDEO = "video";

    private ClientCapabilities() {
    }

    /** 请求头里是否声明了 {@code video}（大小写不敏感、去空白；头缺失 / 空 → false）。 */
    public static boolean includesVideo(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return false;
        }
        for (String cap : headerValue.split(",")) {
            if (VIDEO.equals(cap.strip().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
