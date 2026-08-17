package com.tailtopia.profile.service;

import com.tailtopia.shared.analytics.AnalyticsClient;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * H5 分享页埋点（V1.1.6 Story 1.4 · E-24~E-26）。
 *
 * <p>这一页此前<b>一个埋点都没有</b> —— 分享出去多少、有没有人点、点了之后去了哪，全都不知道。
 *
 * <p><b>三个事件，分工明确</b>：
 * <ul>
 *   <li>{@link #EVENT_OPENED}：页面被打开。<b>服务端上报</b>，计数可靠。</li>
 *   <li>{@link #EVENT_CTA_TAPPED}：点了吸底 CTA。浏览器在<b>点击瞬间</b>发出，计数可靠。</li>
 *   <li>{@link #EVENT_CTA_OUTCOME}：跳转结果（已装 App / 去了商店）。
 *       <b>靠时序推断、属尽力上报、会丢。</b></li>
 * </ul>
 *
 * <p>🛡 <b>后两个必须是两个独立事件，不得合并</b>（epics AC3：合成一个即为不合格）。
 * 理由：结果是猜的、会丢；合并之后连「有多少人点了 CTA」这个最基本、本来最可靠的数
 * 也一起变得不可信了。
 *
 * <p>🛡 <b>不承诺 {@code viewer_state}</b>（访客是否已有账号）：H5 无登录态，服务端做不出这个判断。
 * 埋点清单原话「那个判断做不出来，别写进验收标准」。也<b>不要拿 cookie 有无去猜新老访客</b> —— 那不是同一回事。
 *
 * <p>浏览器侧只与自家域通信（{@code sendBeacon} 打到 {@code /p/track}，由后端转发），
 * <b>不加载任何第三方统计脚本</b>。
 */
@Component
public class CardPageAnalytics {

    public static final String EVENT_OPENED = "pet_card_link_opened";
    public static final String EVENT_CTA_TAPPED = "pet_card_cta_tapped";
    public static final String EVENT_CTA_OUTCOME = "pet_card_cta_outcome";

    /** 页面三态。⚠️ 见 {@link #pageState(boolean)} 对 {@code empty} 判据的说明。 */
    public static final String STATE_FULL = "full";
    public static final String STATE_EMPTY = "empty";
    public static final String STATE_GONE = "gone";

    private final AnalyticsClient analytics;

    public CardPageAnalytics(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    /**
     * E-24：页面被打开。
     *
     * <p>{@code page_state} 是本组<b>最有价值的属性</b>：{@code empty} 占比高，
     * 说明用户在档案还很空的时候就在分享 —— 那该优化的是「什么时候提示分享」，而不是这个页面本身。
     */
    public void linkOpened(String visitorId, String pageState, HttpServletRequest request) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("page_state", pageState);
        props.put("ua_platform", uaPlatform(request));
        String referrerHost = referrerHost(request);
        if (referrerHost != null) {
            props.put("referrer_host", referrerHost);
        }
        capture(visitorId, EVENT_OPENED, props);
    }

    /**
     * 页面状态判定。
     *
     * <p>🔴 <b>{@code empty} 的判据是「没有快乐时刻」，不是「没有里程碑」。</b>
     * Story 1.2 实测发现：<b>建档动作本身就会自动完成一条里程碑</b>（{@code Profil dibuat}），
     * 所以任何档案的里程碑数都 ≥ 1 —— 按里程碑判空态<b>永远判不出来</b>，
     * 这个属性会恒为 {@code full}，AC4 那句「唯一依据」直接落空。
     */
    public static String pageState(boolean hasMoments) {
        return hasMoments ? STATE_FULL : STATE_EMPTY;
    }

    /** 由上报端点调用：事件名与属性都已在端点侧过白名单。 */
    public void capture(String visitorId, String event, Map<String, Object> properties) {
        if (visitorId == null || visitorId.isBlank()) {
            return; // 没有匿名标识就不报 —— 报了也串不进漏斗，只会污染绝对值
        }
        // AnalyticsClient 自身已是 @Async + 吞异常；这里不再加任何阻塞或重试。
        analytics.capture(visitorId, event, properties);
    }

    /** 粗粒度平台，只为分渠道看转化，不做设备指纹。 */
    static String uaPlatform(HttpServletRequest request) {
        String ua = request == null ? null : request.getHeader(HttpHeaders.USER_AGENT);
        if (ua == null || ua.isBlank()) {
            return "other";
        }
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) {
            return "ios";
        }
        if (ua.contains("Android")) {
            return "android";
        }
        if (ua.contains("Windows") || ua.contains("Macintosh") || ua.contains("X11")) {
            return "desktop";
        }
        return "other";
    }

    /**
     * 来源域名。
     *
     * <p>🛡 <b>只取 host，绝不取整条 URL</b> —— 整条 referrer 可能带查询串（搜索词、会话 id 之类），
     * 那属于会连带把别人的数据送进第三方看板的风险。这里只要「从哪个站点过来的」。
     */
    static String referrerHost(HttpServletRequest request) {
        String referrer = request == null ? null : request.getHeader(HttpHeaders.REFERER);
        if (referrer == null || referrer.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(referrer).getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (IllegalArgumentException e) {
            return null; // referrer 畸形就当没有，绝不因此影响页面
        }
    }
}
