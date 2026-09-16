package com.tailtopia.shared.analytics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 服务端埋点护栏（Story 1-2 · AD-S9(b) · 风险 G-5）。
 *
 * <p>{@link AnalyticsClient} 的三条硬约束原先<b>只写在 javadoc 里，没有一行代码守着</b>。
 * 本类把「事件名」与「属性」两道闸落成代码，接在唯一上报通路
 * {@link PostHogAnalyticsClient#capture} 上。
 *
 * <p><b>两级粒度别写反</b>：
 * <table>
 *   <tr><th>违规</th><th>处置</th><th>为什么</th></tr>
 *   <tr><td>事件名不在白名单</td><td><b>整条丢</b> + WARN</td>
 *       <td>未登记的事件名一旦发出去就在 PostHog 里建了一个新 event 类型，清不掉</td></tr>
 *   <tr><td>属性键不在白名单</td><td><b>只丢该键</b>，事件照发</td>
 *       <td>漏斗分母靠事件本身，为一个多余的键把整条事件丢掉是更坏的结果</td></tr>
 *   <tr><td>属性值类型 / 长度不合规</td><td>丢该键</td>
 *       <td>嵌套 map / 长文本是 PII 最常见的载体</td></tr>
 * </table>
 *
 * <p>🔴 <b>本类任何路径都不抛异常</b>：埋点挂掉不得影响业务（{@link AnalyticsClient} 的 javadoc 契约）。
 *
 * <p>🔴 <b>本类不取代 {@code profile/web/CardTrackController} 的局部 sanitize</b> ——
 * 那一层守的是 {@code /p/track} 公网入口，是纵深防御的另一层，两者并存。
 */
@Component
public class AnalyticsEventGuard {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventGuard.class);

    /**
     * 事件名白名单。
     *
     * <p>🔴 <b>漏登记 = 静默关停一条既有埋点</b>。新增服务端事件必须同步加进来。
     */
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            // ---- 既有 5 个（Story 1-2 前就在发，漏一个就等于把它关掉）----
            "milestone_achieved",       // MilestoneAnalyticsListener
            "pet_card_link_opened",     // CardPageAnalytics.linkOpened
            "pet_card_cta_tapped",      // CardTrackController → CardPageAnalytics.capture
            "pet_card_cta_outcome",     // 同上
            "post_share_link_opened",   // PostSharePageAnalytics.linkOpened
            // ---- Story 1-2 新增 5 个：电商支付漏斗的五个结局 ----
            "shop_payment_intent_created",
            "shop_payment_paid",
            "shop_payment_declined",
            "shop_payment_expired",
            "shop_payment_user_cancelled");

    /**
     * 属性键白名单。
     *
     * <p>⚠️ {@code distinct_id} 与 {@code app_env} <b>不在此列</b>：它们是框架注入的，
     * 在本护栏之后才加进 properties，不受本白名单约束。
     */
    private static final Set<String> ALLOWED_PROPERTY_KEYS = Set.of(
            // ---- 既有 8 个 ----
            "code", "level", "path",            // milestone_achieved
            "page_state", "ua_platform",        // 名片页 / CTA
            "referrer_host",                    // pet_card_link_opened（可选）
            "outcome",                          // pet_card_cta_outcome
            "open_method",                      // post_share_link_opened
            // ---- Story 1-2 新增 4 个：支付漏斗维度，全是枚举与数值，无一条可反推到人 ----
            "order_amount",                     // long，订单总额
            "failure_category",                 // PaymentFailureCategory 名
            "pay_channel",                      // PayChannel 名
            "has_pawcoin");                     // boolean

    /**
     * 字符串属性值的长度上限。
     *
     * <p>与 App 侧 {@code petgo_app/lib/core/analytics/analytics.dart:58} 的
     * {@code _maxStringValueLen} <b>同值</b>——两端不一致会让同一个属性在前后端被截成不同长度，
     * 漏斗按值分组时对不上。
     */
    private static final int MAX_STRING_LEN = 64;

    /**
     * 事件名是否放行。不放行时打一行 WARN（<b>只记事件名，不记 properties</b>——照
     * {@code PostHogAnalyticsClient} 的既有纪律）。
     */
    public boolean allowsEvent(String event) {
        if (event != null && ALLOWED_EVENTS.contains(event)) {
            return true;
        }
        log.warn("analytics event rejected: event={}", event);
        return false;
    }

    /**
     * 逐键过滤属性：键不在白名单、或值类型/长度不合规 → <b>丢该键，事件照发</b>。
     *
     * <p>🔴 <b>值类型只允许 {@code String} / {@code Number} / {@code Boolean}</b>，容器类型一律丢。
     * App 侧的 {@code scrub} 为此踩过一次（Story 9.2：行级归因把 {@code items[]} 这种「map 的数组」
     * 带进了埋点，只要有人往行里加个收件人名就会绕过全部三道规则直接发出去）。服务端这道闸
     * 从形状上杜绝同类问题，比递归过滤更省事也更难写错。
     *
     * @return <b>新 map</b>，入参不被就地修改
     */
    public Map<String, Object> filterProperties(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (!ALLOWED_PROPERTY_KEYS.contains(e.getKey())) {
                continue;
            }
            if (!isAllowedValue(e.getValue())) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 值闸：标量放行、字符串限长、其余（含 null / Map / List / 任意对象）丢弃。 */
    private static boolean isAllowedValue(Object v) {
        if (v instanceof String s) {
            return s.length() <= MAX_STRING_LEN;
        }
        return v instanceof Number || v instanceof Boolean;
    }
}
