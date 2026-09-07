package com.tailtopia.content.service;

import com.tailtopia.profile.service.CardPageAnalytics;
import com.tailtopia.shared.analytics.AnalyticsClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 单条内容分享页 H5 埋点（V1.1.6 Story 10.1 · E-14 {@code post_share_link_opened}）。
 *
 * <p>这一页此前<b>一个埋点都没有</b> —— 分享卡做出去了，但「有没有人点开」这个最基本的问题
 * 回答不了，整条 FR-73 的漏斗断在最后一环（E-11 点分享 → E-12 出图 → E-13 递出 → ？）。
 *
 * <p><b>服务端上报</b>，与名片页 {@link CardPageAnalytics} 同一条路子：复用既有
 * {@code PostHogAnalyticsClient}，<b>不引入任何前端统计 SDK</b>（V1.1.2 起的既定原则）。
 * 匿名 {@code distinct_id} 也复用同一个 {@code tt_vid} cookie —— 同一个访客先点名片、
 * 后点某条内容，在看板上是同一个人。
 *
 * <h2>🔴 三个属性里只有一个做得出来</h2>
 * 清单 / PRD 给 E-14 列了三个属性，逐条核过之后<b>只上报 {@code open_method}</b>：
 * <ul>
 *   <li><b>{@code open_method}（报）</b>：{@code qr} / {@code link}。判据是链接尾部的
 *       {@code ?src=qr} —— 客户端只把带标记的那一份印进二维码（见 Dart 的
 *       {@code ShareCardData.qrUrl}）。这是<b>下载二维码唯一的验收依据</b>：
 *       它占了卡片页脚近一半版面，{@code qr} 长期极低就该撤掉。</li>
 *   <li><b>{@code viewer_state}（不报）</b>：H5 是无登录态公开页，服务端拿不到
 *       「这个访客是否已有账号」。埋点清单原话「那个判断做不出来，别写进验收标准」，
 *       架构 AD-16 Rule 4 同。🛡 <b>也不要拿 cookie 有无去猜新老访客</b> —— 那不是同一回事。</li>
 *   <li><b>{@code is_app_installed}（不报）</b>：同理，一次 GET 请求里没有任何可靠信号能判断
 *       访客手机上装没装 App。名片页那边是靠 CTA 的三级降级链<b>事后</b>推断的
 *       （E-26 {@code outcome}），而本页没有那条链。</li>
 * </ul>
 *
 * <p>额外报一个 {@code ua_platform}（沿用名片页的粗粒度口径）：二维码几乎只可能被
 * <b>另一台设备</b>扫到，不拆平台就看不出这个差别。
 */
@Component
public class PostSharePageAnalytics {

    public static final String EVENT_OPENED = "post_share_link_opened";

    /** {@code open_method} 词表。 */
    public static final String METHOD_QR = "qr";
    public static final String METHOD_LINK = "link";

    /** 二维码专用标记：客户端只把带它的那一份 URL 印进码里。 */
    static final String QR_SRC_PARAM = "src";
    static final String QR_SRC_VALUE = "qr";

    private final AnalyticsClient analytics;

    public PostSharePageAnalytics(AnalyticsClient analytics) {
        this.analytics = analytics;
    }

    /**
     * E-14：分享链接被打开。
     *
     * <p>🛡 <b>失效页也报</b>（与名片页 {@code gone} 同口径）：「打开的是一个已失效的链接」
     * 本身就是要看的数 —— 只报成功的话，分母里会缺掉这一类，
     * 而「分享出去的东西过期得多快」正是要观测的问题之一。
     */
    public void linkOpened(String visitorId, HttpServletRequest request) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("open_method", openMethod(request));
        props.put("ua_platform", CardPageAnalytics.uaPlatform(request));
        if (visitorId == null || visitorId.isBlank()) {
            return; // 没有匿名标识就不报 —— 报了也串不进漏斗，只会污染绝对值
        }
        // AnalyticsClient 自身已是 @Async + 吞异常；这里不再加任何阻塞或重试。
        analytics.capture(visitorId, EVENT_OPENED, props);
    }

    /**
     * 判定进来的方式。
     *
     * <p>⚠️ 默认 {@code link} 而不是 {@code unknown}：没有标记就是从文字链接来的，
     * 这不是「不知道」。多一个 {@code unknown} 档只会让两个真实取值的比例失真。
     */
    static String openMethod(HttpServletRequest request) {
        String src = request == null ? null : request.getParameter(QR_SRC_PARAM);
        return QR_SRC_VALUE.equalsIgnoreCase(src) ? METHOD_QR : METHOD_LINK;
    }
}
