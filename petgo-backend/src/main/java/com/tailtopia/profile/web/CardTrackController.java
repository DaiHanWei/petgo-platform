package com.tailtopia.profile.web;

import com.tailtopia.profile.service.CardPageAnalytics;
import com.tailtopia.shared.analytics.AnonymousVisitorId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * H5 分享页的埋点上报口（V1.1.6 Story 1.4 · E-25 / E-26）。
 *
 * <p><b>为什么需要它</b>：「点了 CTA」和「跳转结果」都发生在浏览器里，服务端看不到。
 * 浏览器用 {@code navigator.sendBeacon} 打到这里，由后端转发 PostHog ——
 * 这样<b>浏览器只与自家域通信</b>，不加载任何第三方统计脚本（沿用 V1.1.2 服务端埋点的既定原则）。
 *
 * <h2>🛡 这是全项目唯一一个「对公网开放的写入口」，边界必须扎紧</h2>
 * 分享页本身就是公开的，所以这个端点也必然公开。不设边界等于给任何人一条
 * <b>往看板里灌任意事件的通道</b>，而脏数据一旦混进去<b>无法追溯清洗</b>。四道闸：
 * <ol>
 *   <li><b>事件名白名单</b>：只认这两个事件，其余静默丢弃。</li>
 *   <li><b>属性白名单 + 取值枚举</b>：只放行已知的键与取值，别的一概不转发。</li>
 *   <li><b>{@code distinctId} 只从 cookie 取</b> —— <b>绝不信任请求体里传来的任何 id</b>，
 *       否则伪造者可以把事件挂到任意访客身上。</li>
 *   <li><b>永远只回 204、不回业务数据</b>：既不能被当探测接口，也不让埋点失败影响页面。</li>
 * </ol>
 */
@RestController
public class CardTrackController {

    /** 🛡 只有这两个事件允许由浏览器上报。E-24 是服务端自己报的，不在此列。 */
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            CardPageAnalytics.EVENT_CTA_TAPPED,
            CardPageAnalytics.EVENT_CTA_OUTCOME);

    /** 🛡 属性白名单。不在表里的键一律丢弃，不转发。 */
    private static final Set<String> ALLOWED_PROPS = Set.of("page_state", "ua_platform", "outcome");

    /**
     * 🛡 {@code outcome} 的受控取值。
     * <ul>
     *   <li>{@code app_opened} —— 深链生效，访客已装 App</li>
     *   <li>{@code store_redirect} —— 深链没接住，落到应用商店（未装）</li>
     *   <li>{@code desktop_fallback} —— 桌面浏览器，直接落下载页</li>
     * </ul>
     * 这三个的分布 = <b>已装 / 未装比例</b>，直接决定 CTA 文案该写「打开 App」还是「下载 App」。
     */
    private static final Set<String> ALLOWED_OUTCOMES =
            Set.of("app_opened", "store_redirect", "desktop_fallback");

    private static final Set<String> ALLOWED_PAGE_STATES =
            Set.of(CardPageAnalytics.STATE_FULL, CardPageAnalytics.STATE_EMPTY);

    private static final Set<String> ALLOWED_PLATFORMS = Set.of("ios", "android", "desktop", "other");

    private final CardPageAnalytics analytics;

    public CardTrackController(CardPageAnalytics analytics) {
        this.analytics = analytics;
    }

    /**
     * 接收浏览器上报。<b>永远回 204</b> —— 无论是否被白名单放行。
     *
     * <p>⚠️ 对非法请求也回 204 是刻意的：回 4xx 等于告诉探测者「这个事件名不对、换一个试试」，
     * 反而帮他摸清白名单。静默丢弃即可。
     */
    @PostMapping("/p/track")
    public ResponseEntity<Void> track(@RequestBody(required = false) TrackRequest body,
            HttpServletRequest request) {
        // 🛡 只认 cookie 里的匿名标识，请求体里的任何 id 一概无视。
        String visitorId = AnonymousVisitorId.readCookie(request);
        if (visitorId == null || body == null || body.event() == null
                || !ALLOWED_EVENTS.contains(body.event())) {
            return ResponseEntity.noContent().build();
        }
        analytics.capture(visitorId, body.event(), sanitize(body.props()));
        return ResponseEntity.noContent().build();
    }

    /** 只留白名单里的键，且每个键的取值也要在受控集合内。 */
    private static Map<String, Object> sanitize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = e.getKey();
            if (!ALLOWED_PROPS.contains(key) || !(e.getValue() instanceof String value)) {
                continue;
            }
            boolean ok = switch (key) {
                case "outcome" -> ALLOWED_OUTCOMES.contains(value);
                case "page_state" -> ALLOWED_PAGE_STATES.contains(value);
                case "ua_platform" -> ALLOWED_PLATFORMS.contains(value);
                default -> false;
            };
            if (ok) {
                out.put(key, value);
            }
        }
        return out;
    }

    /**
     * 上报请求体。
     *
     * <p>⚠️ <b>刻意没有 distinctId 字段</b> —— 不是忘了，是不接受。加上它就等于开门。
     */
    public record TrackRequest(String event, Map<String, Object> props) {
    }
}
