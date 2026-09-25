package com.tailtopia.shop.repurchase.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标：复购卡 wire（Story 4-4 AC4 · C5）。
 *
 * <p>⚠️ <b>本类是 {@link RepurchaseCardView} 的第一条测试</b> —— 在 Story 4-4 之前，
 * 这个对外 DTO 在后端一条测试覆盖都没有。
 *
 * <p>三方同步点：
 * <ul>
 *   <li>后端 record —— {@link RepurchaseCardView}</li>
 *   <li>App DTO —— {@code petgo_app/lib/features/shop/domain/shop_repurchase.dart}
 *       （{@code RepurchaseCard.fromJson}）</li>
 *   <li>App 替身 —— {@code petgo_app/test/shop/repurchase_zones_v2_test.dart} 的内联构造
 *       （🔴 本仓已无 {@code mock_backend.dart}，C5 的「四处」在本仓实为三处）</li>
 * </ul>
 *
 * <p>🔴 mapper 配 {@code NON_NULL}，镜像 {@code application.yml} 的
 * {@code spring.jackson.default-property-inclusion: non_null}。
 */
class RepurchaseCardViewContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    /** 顶层键集（12 个，含 Story 4-4 追加的 {@code price}）。 */
    private static final Set<String> KEYS = Set.of(
            "triggerId", "triggerType", "skuToken", "productToken", "productName", "petName",
            "estimatedDepletionDate", "daysLeft",
            "dailyGrams", "remainingGrams", "purchasedOn",
            "price");

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    private RepurchaseCardView card(Long price) {
        return new RepurchaseCardView(7L, "FOOD_LOW", "sku-tok-1", "prd-tok-1",
                "Royal Canin Adult Dog", "Momo", LocalDate.of(2026, 9, 25), 9L,
                120, 1_080, LocalDate.of(2026, 8, 18), price);
    }

    @Test
    @DisplayName("全字段样本的键集恰好是契约里的 12 个")
    void fullCardHasExactlyTheContractFields() {
        // 🔴 集合相等而不是「包含」：只断言包含的话，将来误加一个字段不会变红，
        //    而 App 的 fromJson 对多出来的键是静默忽略的 —— 契约就这样悄悄漂了。
        assertThat(wire(card(189_000L)).keySet()).isEqualTo(KEYS);
    }

    @Test
    @DisplayName("🔴 price 是整数最小币种单位，不是小数")
    void priceIsAWholeNumberInSmallestUnit() {
        // IDR 无小数。用 DECIMAL/double 会在这里渲染成 189000.0，
        // App 的 `json['price'] as int?` 当场抛 —— 而且是只在真实数据上才抛。
        assertThat(wire(card(189_000L)).get("price")).isEqualTo(189_000L);
        assertThat(json.writeValueAsString(card(189_000L)))
                .contains("\"price\":189000")
                .doesNotContain("189000.0");
    }

    /**
     * 🔴 <b>这条把「后端怎么表达没有价格」钉死。</b>
     *
     * <p>NON_NULL 下 {@code price = null} 是<b>整键消失</b>，不是「键在、值为 null」。
     * App 侧 {@code json['price'] as int?} 对<b>缺键与 null 同样得到 null</b> ——
     * 两端在此对齐，`hasPrice` 于是为 false、整行不画。
     * 若哪天有人给这个 mapper 关掉 NON_NULL，这条会红。
     */
    @Test
    @DisplayName("🔴 price 为 null → 整键省略（其余 11 键一个不少）")
    void nullPriceDisappearsFromTheWire() {
        Map<String, Object> w = wire(card(null));

        assertThat(w).doesNotContainKey("price");
        assertThat(w.keySet()).containsExactlyInAnyOrderElementsOf(
                KEYS.stream().filter(k -> !k.equals("price")).toList());
    }

    @Test
    @DisplayName("🔴 skuToken 仍在下发 —— 本 story 不许把它删掉")
    void skuTokenIsStillEmitted() {
        // 后端一直在发它，只是 Dart 侧一直丢着没读（Story 4-4 顺手补上）。
        assertThat(wire(card(189_000L))).containsEntry("skuToken", "sku-tok-1");
    }

    @Test
    @DisplayName("推算依据三项同时为 null 时一起消失 —— 它们是「缺一不可」的一组")
    void basisTrioVanishesTogether() {
        // 设计稿把「日均用量 · 剩余量 · 购买日期」列为缺一不可：
        // 它是用户信任这条推荐的唯一凭据。任一算不出就整组 null，前端整卡不渲染。
        var noBasis = new RepurchaseCardView(7L, "FOOD_LOW", "sku-tok-1", "prd-tok-1",
                "Royal Canin Adult Dog", "Momo", LocalDate.of(2026, 9, 25), 9L,
                null, null, null, 189_000L);

        assertThat(wire(noBasis))
                .doesNotContainKeys("dailyGrams", "remainingGrams", "purchasedOn")
                .containsKey("price");
    }

    @Test
    @DisplayName("MAX_CARDS=2：capped 截断到 2 张（FR-93）")
    void cappedKeepsAtMostTwo() {
        var three = List.of(card(1L), card(2L), card(3L));

        assertThat(RepurchaseCardView.capped(three)).hasSize(RepurchaseCardView.MAX_CARDS);
        assertThat(RepurchaseCardView.MAX_CARDS).isEqualTo(2);
    }
}
