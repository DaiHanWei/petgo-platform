package com.tailtopia.shop.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标：订单详情 wire（Story 1-1 AC4 · C5 三处同改之「契约 test」腿）。
 *
 * <p>三方同步点：
 * <ul>
 *   <li>后端 record —— {@link ShopOrderDetailView}</li>
 *   <li>App DTO —— {@code petgo_app/lib/features/shop/domain/shop_order_detail.dart}
 *       （{@code ShopOrderDetail.fromJson}）</li>
 *   <li>App mock —— 🔴 <b>本仓已不存在</b>：mock 子系统整体删除于 {@code 8e85b40d}，C5 的「四处」在本仓实为三处</li>
 * </ul>
 *
 * <p>🔴 <b>mapper 必须配 {@code NON_NULL}</b>（{@code application.yml:235}
 * {@code spring.jackson.default-property-inclusion: non_null}）——用裸 {@code ObjectMapper}
 * 会钉出一份线上永远不会出现的 22 键 JSON：真实响应把 null 字段整键省略。
 * 钉错形状的契约测试是绿的，但它保护的是一个不存在的契约。
 */
class ShopOrderDetailViewContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    /**
     * 顶层键集（顺序即 record 组件顺序）。
     *
     * <p>⚠️ <b>惯例是新字段末尾追加</b>，插中间会让按位置构造的调用方静默错位。
     * Story 4-3 的 {@code displayNo} 是<b>刻意的例外</b>：它在语义上就该紧挨 {@code orderToken}
     * （一个是内部查询键、一个是给人看的号，成对出现才读得懂）。
     * 破例的前提是本仓只有<b>两个</b>按位置构造的调用点（{@code ShopOrderDetailView.of} 与本类），
     * 且两个都在本次 diff 里一起改了。
     * 🔴 但「两个 String 挨在一起」正是编译器抓不住的那种错位 ——
     * 所以另加了 {@link #displayNoAndOrderTokenAreNotSwapped()} 专门钉这一处。
     */
    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "orderToken", "displayNo", "status", "goodsSubtotal", "shippingFee", "shippingDiscount",
            "totalAmount", "payChannel", "coinAmount", "cashAmount", "expiresAt", "createdAt",
            "paymentIntentToken", "shipTo", "lines", "shippedAt", "deliveredAt", "completedAt",
            "returnWindowEndsAt", "packages", "attributionSource",
            "paymentStatus", "paymentFailureCategory");

    /** 全字段非 null 的样本 —— 只有它能把「字段集」钉全（NON_NULL 下 null 字段整键消失）。 */
    private ShopOrderDetailView fullyPopulated(String paymentStatus, String failureCategory) {
        Instant t = Instant.parse("2026-09-16T10:00:00Z");
        return new ShopOrderDetailView(
                "ord-tok-1", "TOKO-20260916-7M4KQ2", "COMPLETED", 100_000L, 15_000L, 5_000L, 110_000L, "MIXED",
                10_000L, 100_000L, t, t, "pi-tok-1",
                new ShopOrderDetailView.ShipTo("Budi", "0811", "DKI", "Jakarta Selatan",
                        "Kebayoran", "Jl. Melati 1", "12110"),
                List.of(new ShopOrderDetailView.Line("Royal Canin 2kg", "2kg", 100_000L, 1,
                        100_000L, "NO_RETURN", "https://cdn.example/x.jpg")),
                t, t, t, t,
                List.of(new ShopOrderDetailView.Package("JNE", "JNE", "JN1", "https://jne/JN1",
                        "DELIVERED", t, t)),
                "unknown", paymentStatus, failureCategory);
    }

    @Test
    @DisplayName("顶层字段集恰好 23 个，displayNo / paymentStatus / paymentFailureCategory 在列")
    void fullOrderHasExactlyTheContractFields() {
        assertThat(wire(fullyPopulated("FAILED", "GATEWAY_DECLINED")).keySet())
                .isEqualTo(TOP_LEVEL_KEYS);
    }

    @Test
    @DisplayName("🔴 Story 4-3：displayNo 与 orderToken 没有被位置错位串掉")
    void displayNoAndOrderTokenAreNotSwapped() {
        // 两个相邻的 String 组件，编译器换过来也一样过 —— 而线上的后果是
        // 用户看到 22 位内部 token（正是本 story 要修的那个毛病），
        // 同时 App 拿 TOKO-… 去当路由键请求详情，直接 404。
        Map<String, Object> w = wire(fullyPopulated("PAID", null));

        assertThat(w.get("orderToken")).isEqualTo("ord-tok-1");
        assertThat(w.get("displayNo")).isEqualTo("TOKO-20260916-7M4KQ2");
        assertThat(String.valueOf(w.get("displayNo")))
                .as("展示号必须是给人看的那一个：TOKO 前缀 + WIB 日期 + 6 位 Crockford")
                .matches("^TOKO-\\d{8}-[0-9A-HJKMNP-TV-Z]{6}$");
        assertThat(String.valueOf(w.get("orderToken")))
                .as("内部 token 不是展示号，它不该长成 TOKO-… 的样子")
                .doesNotStartWith("TOKO-");
    }

    @Test
    @DisplayName("🔒 响应不含 gatewayMeta / gateway_meta / gatewayRef 任何形态")
    void neverLeaksGatewayMeta() {
        String out = json.writeValueAsString(fullyPopulated("FAILED", "GATEWAY_DECLINED"));
        assertThat(out)
                .doesNotContain("gatewayMeta")
                .doesNotContain("gateway_meta")
                .doesNotContain("gatewayRef");
    }

    @Test
    @DisplayName("枚举线是 UPPER_SNAKE 字面量，不是 ordinal 数字")
    void enumWireFormatIsUpperSnakeLiteral() {
        Map<String, Object> w = wire(fullyPopulated("FAILED", "GATEWAY_DECLINED"));
        assertThat(w.get("paymentStatus")).isEqualTo("FAILED");
        assertThat(w.get("paymentFailureCategory")).isEqualTo("GATEWAY_DECLINED");

        String out = json.writeValueAsString(fullyPopulated("EXPIRED", "USER_CANCELLED"));
        assertThat(out)
                .contains("\"paymentStatus\":\"EXPIRED\"")
                .contains("\"paymentFailureCategory\":\"USER_CANCELLED\"");
    }

    @Test
    @DisplayName("纯 PawCoin 单（无支付意图）→ 两个键在线上整键省略，App 按缺键读成 null")
    void pureCoinOrderOmitsBothKeysOnTheWire() {
        Map<String, Object> w = wire(fullyPopulated(null, null));

        // NON_NULL：不是「键在、值为 null」，而是键根本不出现。
        // App 侧 j['paymentStatus']?.toString() 对缺键与 null 同样得到 null —— 两端在此对齐。
        assertThat(w).doesNotContainKeys("paymentStatus", "paymentFailureCategory");
        // 其余 21 个键一个不少 —— 省的只能是这两个。
        assertThat(w.keySet()).containsExactlyInAnyOrderElementsOf(TOP_LEVEL_KEYS.stream()
                .filter(k -> !k.equals("paymentStatus") && !k.equals("paymentFailureCategory"))
                .toList());
    }

    @Test
    @DisplayName("失败但非用户取消：paymentFailureCategory 在，paymentStatus 也在")
    void failedOrderCarriesBothKeys() {
        Map<String, Object> w = wire(fullyPopulated("FAILED", "EXPIRED"));
        assertThat(w).containsEntry("paymentStatus", "FAILED")
                .containsEntry("paymentFailureCategory", "EXPIRED");
    }
}
