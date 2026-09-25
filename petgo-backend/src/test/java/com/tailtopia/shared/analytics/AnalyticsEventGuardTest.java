package com.tailtopia.shared.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：服务端埋点护栏（Story 1-2 AC1/AC2/AC5）。
 *
 * <p>🔴 <b>本类是变异验证的靶子</b>（AC5）。本工作线出过三次「护栏假绿」——
 * 测试是绿的，但把被守护的那行代码删掉，测试<b>还是</b>绿的。两条标了
 * 「变异靶子」的用例必须在删掉对应判断后变红：
 * <ul>
 *   <li>{@link #rejectsUnknownEventName()} ← 删 {@code AnalyticsEventGuard.allowsEvent} 里的
 *       {@code ALLOWED_EVENTS.contains(event)} 判断</li>
 *   <li>{@link #rejectsUnknownPropertyKey()} ← 删 {@code filterProperties} 里的
 *       {@code if (!ALLOWED_PROPERTY_KEYS.contains(e.getKey())) continue;} 那一段</li>
 * </ul>
 */
class AnalyticsEventGuardTest {

    private final AnalyticsEventGuard guard = new AnalyticsEventGuard();

    // ---------- 事件名白名单（AC1） ----------

    /**
     * 🔴 这里**刻意写字面量而不是引常量**，与 {@code AnalyticsEventGuard} 的做法相反 ——
     * 那边引常量是为了跟着生产方一起动，这边写字面量是为了<b>钉住线上的事件名</b>。
     * 两者合起来：改常量值 → 白名单与生产方同步跟上（埋点不会静默停掉），但本用例变红，
     * 逼改动者确认「PostHog 里这条事件确实要改名」。
     */
    @Test
    @DisplayName("既有 5 个事件全部放行 —— 漏登记一个就等于静默关停一条线上埋点")
    void allowsAllPreExistingEvents() {
        assertThat(guard.allowsEvent("milestone_achieved")).isTrue();
        assertThat(guard.allowsEvent("pet_card_link_opened")).isTrue();
        assertThat(guard.allowsEvent("pet_card_cta_tapped")).isTrue();
        assertThat(guard.allowsEvent("pet_card_cta_outcome")).isTrue();
        assertThat(guard.allowsEvent("post_share_link_opened")).isTrue();
    }

    @Test
    @DisplayName("本 story 新增的 5 个支付事件全部放行")
    void allowsAllShopPaymentEvents() {
        assertThat(guard.allowsEvent("shop_payment_intent_created")).isTrue();
        assertThat(guard.allowsEvent("shop_payment_paid")).isTrue();
        assertThat(guard.allowsEvent("shop_payment_declined")).isTrue();
        assertThat(guard.allowsEvent("shop_payment_expired")).isTrue();
        assertThat(guard.allowsEvent("shop_payment_user_cancelled")).isTrue();
        // 2026-09-25 KTP 付费漏斗
        assertThat(guard.allowsEvent("ktp_unlock_succeeded")).isTrue();
        assertThat(guard.allowsEvent("ktp_unlock_failed")).isTrue();
    }

    @Test
    @DisplayName("🎯 变异靶子：白名单外的事件名被拒（含 typo 形态与大小写变体）")
    void rejectsUnknownEventName() {
        // 删掉 allowsEvent 里的 ALLOWED_EVENTS.contains 判断，本用例必须变红。
        assertThat(guard.allowsEvent("shop_payment_paid_")).isFalse();   // 尾部多一个下划线
        assertThat(guard.allowsEvent("SHOP_PAYMENT_PAID")).isFalse();    // 大小写变体
        assertThat(guard.allowsEvent("Shop_Payment_Paid")).isFalse();
        assertThat(guard.allowsEvent("shop_order_created")).isFalse();   // 没登记过的新名字
        assertThat(guard.allowsEvent("")).isFalse();
        assertThat(guard.allowsEvent(null)).isFalse();
    }

    // ---------- 属性键白名单（AC2） ----------

    @Test
    @DisplayName("白名单内的属性键原样保留（既有 8 个 + 新增 4 个）")
    void keepsAllowedPropertyKeys() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("code", "C-M5");
        raw.put("level", 3);
        raw.put("path", "consult");
        raw.put("page_state", "HAS_PET");
        raw.put("ua_platform", "android");
        raw.put("referrer_host", "wa.me");
        raw.put("outcome", "OPENED");
        raw.put("open_method", "deeplink");
        raw.put("order_amount", 285_000L);
        raw.put("failure_category", "GATEWAY_DECLINED");
        raw.put("pay_channel", "QRIS");
        raw.put("has_pawcoin", true);
        raw.put("method", "QRIS");
        raw.put("price_idr", 10_000L);
        raw.put("failure_reason", "EXPIRED");

        assertThat(guard.filterProperties(raw)).hasSize(15).containsAllEntriesOf(raw);
    }

    @Test
    @DisplayName("🎯 变异靶子：白名单外的属性键被逐键丢弃，其中三个是具体 PII 键")
    void rejectsUnknownPropertyKey() {
        // 删掉 filterProperties 里的「键不在白名单就 continue」那一段，本用例必须变红，
        // 失败信息会直指「本该被丢的属性键漏出去了」。
        Map<String, Object> raw = new HashMap<>();
        raw.put("code", "C-M5");              // 白名单内，应保留
        raw.put("receiver_name", "Budi");     // 🔒 PII
        raw.put("phone", "081234567890");     // 🔒 PII
        raw.put("order_token", "ord-abc123"); // 对外标识，进埋点等于把标识面扩到第三方
        raw.put("email", "a@b.c");
        raw.put("whatever", "x");

        Map<String, Object> out = guard.filterProperties(raw);

        assertThat(out).containsOnlyKeys("code");
        assertThat(out).as("PII 与对外标识一个都不许漏出去")
                .doesNotContainKeys("receiver_name", "phone", "order_token", "email", "whatever");
    }

    @Test
    @DisplayName("属性键不合规只丢该键，事件本身照发 —— 两级粒度不能写反")
    void unknownKeyDoesNotKillTheWholeEvent() {
        Map<String, Object> out = guard.filterProperties(
                Map.of("order_amount", 100L, "receiver_name", "Budi"));

        // 为一个多余的键把整条事件丢掉是更坏的结果：漏斗的分母靠事件本身。
        assertThat(out).containsEntry("order_amount", 100L).hasSize(1);
        assertThat(guard.allowsEvent("shop_payment_paid")).isTrue();
    }

    // ---------- 值闸（AC2 后两条） ----------

    @Test
    @DisplayName("容器类型的值一律丢弃 —— Map / List 是 PII 最常见的载体")
    void rejectsContainerValues() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("code", Map.of("nested", "value"));
        raw.put("path", List.of(Map.of("receiverName", "Budi")));
        raw.put("outcome", new Object());
        raw.put("level", null);

        assertThat(guard.filterProperties(raw)).isEmpty();
    }

    @Test
    @DisplayName("字符串长度上限 64：64 保留、65 丢弃（与 App 侧 _maxStringValueLen 同值）")
    void enforcesStringLengthCap() {
        String len64 = "a".repeat(64);
        String len65 = "a".repeat(65);

        assertThat(guard.filterProperties(Map.of("code", len64)))
                .containsEntry("code", len64);
        assertThat(guard.filterProperties(Map.of("code", len65))).isEmpty();
    }

    @Test
    @DisplayName("Boolean 与各种 Number 保留")
    void keepsScalarValues() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("has_pawcoin", false);
        raw.put("order_amount", 285_000L);
        raw.put("level", 3);

        assertThat(guard.filterProperties(raw)).hasSize(3);
    }

    // ---------- 契约细节 ----------

    @Test
    @DisplayName("返回新 map，入参不被就地修改")
    void doesNotMutateInput() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("code", "C-M5");
        raw.put("receiver_name", "Budi");

        Map<String, Object> out = guard.filterProperties(raw);

        assertThat(raw).hasSize(2).containsKey("receiver_name");
        assertThat(out).isNotSameAs(raw);
    }

    @Test
    @DisplayName("null properties → 空 map，不抛异常（埋点挂了不得影响业务）")
    void nullPropertiesYieldEmptyMap() {
        assertThat(guard.filterProperties(null)).isEmpty();
    }

    @Test
    @DisplayName("distinct_id / app_env 不在属性白名单里 —— 它们是护栏之后由框架注入的")
    void frameworkInjectedKeysAreNotInThePropertyWhitelist() {
        assertThat(guard.filterProperties(Map.of("distinct_id", "hash", "app_env", "prod")))
                .isEmpty();
    }
}
