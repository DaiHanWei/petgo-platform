package com.tailtopia.shop.cart.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标：钉死购物车对外 JSON 形状（Story 4-1 AC8 · CROSS-STORY-DECISIONS C5）。
 *
 * <p><b>四处必须同步，任一漂移即契约破坏：</b>
 * <ul>
 *   <li>后端 record —— {@link CartView} / {@link CartView.CartLine}（本测试钉的对象），<b>4-1 交付</b></li>
 *   <li>后端契约 test —— 本文件，<b>4-1 交付</b></li>
 *   <li>App data DTO —— {@code petgo_app/lib/features/shop/domain/shop_cart.dart}，<b>4-2 交付</b></li>
 *   <li>App 替身 —— {@code petgo_app/test/shop/cart_page_v2_test.dart} 的 {@code _FakeCartController}，
 *       <b>4-2 交付</b>（⚠️ 本仓已无 {@code mock_backend.dart}，C5 条文里的「App mock」在本仓的等价落点是它）</li>
 * </ul>
 *
 * <p>纯 Jackson 序列化，<b>无 Spring 上下文 / 无 DB</b> → 云端 headless 可跑（L0）。
 * 序列化器镜像生产配置（{@code application.yml → spring.jackson.default-property-inclusion=non_null}）。
 */
class CartViewContractTest {

    /** 与生产一致的 NON_NULL 序列化（Jackson 3 / {@code tools.jackson}）。 */
    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    /**
     * {@code CartView} 顶层字段集。
     *
     * <p>🔴 Story 4-1 追加 {@code selectedSubtotal} / {@code selectedCount}，
     * 而 {@code subtotal} / {@code itemCount} <b>原样保留</b> —— 老版本 App 读的是后两个。
     */
    private static final Set<String> VIEW_FIELDS = Set.of(
            "lines", "invalidLines", "subtotal", "selectedSubtotal", "itemCount", "selectedCount");

    /** {@code CartLine} 完整字段集（12 个，含 Story 4-1 追加的 {@code selected}）。 */
    private static final Set<String> LINE_FIELDS = Set.of(
            "skuToken", "productToken", "productName", "specName", "price", "qty",
            "mainImageUrl", "availableStock", "invalidReason", "entrySource", "triggerType",
            "selected");

    private Map<String, Object> wire(Object dto) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = json.convertValue(dto, Map.class);
        return m;
    }

    private static CartView.CartLine line(String token, long price, int qty, boolean selected,
            String invalidReason) {
        return new CartView.CartLine(token, "p-" + token, "Royal Canin", "3 kg", price, qty,
                "https://cdn.petgo/p/a.jpg", 10L, invalidReason, "SHOP_HOME", "TRIGGER_CARD",
                selected);
    }

    @Test
    @DisplayName("CartLine 完整形态的字段集恰好是契约里的 12 个")
    void cartLineFullShapeHasExactlyTheContractFields() {
        // 「完整形态」= 每个可空字段都有值。invalidReason 必须非空，
        // 否则 NON_NULL 会把它省掉，这条断言就钉不到全集。
        Map<String, Object> m = wire(line("sku-1", 100_000L, 2, true,
                CartView.REASON_OUT_OF_STOCK));

        // 🔴 用「集合相等」而不是「包含」：只断言包含的话，将来误加一个字段不会变红，
        //    而 App 侧的 fromJson 对多出来的键是静默忽略的 —— 契约就这样悄悄漂了。
        assertThat(m.keySet())
                .as("CartLine 字段集必须与 App shop_cart.dart 的 CartLine.fromJson 一一对应（C5）")
                .isEqualTo(LINE_FIELDS);
        assertThat(m.get("selected")).isEqualTo(true);
    }

    @Test
    @DisplayName("CartView 顶层字段集恰好是契约里的 6 个")
    void cartViewShapeHasExactlyTheContractFields() {
        CartView v = new CartView(List.of(line("sku-1", 100_000L, 2, true, null)),
                List.of(), 200_000L, 200_000L, 2, 2);

        assertThat(wire(v).keySet()).isEqualTo(VIEW_FIELDS);
    }

    @Test
    @DisplayName("🔴 两行有效只选一行时：subtotal 仍是**两行**合计，selectedSubtotal 才是一行")
    void subtotalStaysWholeCartWhileSelectedSubtotalNarrows() {
        // 这条是防止有人「顺手」把 subtotal 改成选中合计的护栏。
        // 线上老版本 App 的购物车底栏读的就是 subtotal，而那个界面**没有勾选框可点** ——
        // 金额变了他既解释不了也纠正不了（AD-S6 写「subtotal 语义不变」的全部理由）。
        CartView.CartLine picked = line("sku-1", 100_000L, 2, true, null);
        CartView.CartLine unpicked = line("sku-2", 50_000L, 3, false, null);
        CartView v = new CartView(List.of(picked, unpicked), List.of(),
                /* subtotal  */ 100_000L * 2 + 50_000L * 3,
                /* selected  */ 100_000L * 2,
                /* itemCount */ 5,
                /* selected  */ 2);

        Map<String, Object> m = wire(v);
        assertThat(m.get("subtotal"))
                .as("subtotal 必须是全部有效行合计 —— 改成选中合计会让老版本用户看到无法纠正的金额")
                .isEqualTo(350_000L);
        assertThat(m.get("itemCount")).as("itemCount 是件数，口径同样不变").isEqualTo(5);
        assertThat(m.get("selectedSubtotal")).isEqualTo(200_000L);
        assertThat(m.get("selectedCount")).as("selectedCount 与 itemCount 同为件数口径").isEqualTo(2);
    }

    @Test
    @DisplayName("失效行的 selected 照实下发，不被改写成 false")
    void invalidLineReportsItsSelectedFlagHonestly() {
        CartView.CartLine dead = line("sku-9", 80_000L, 1, true, CartView.REASON_DELISTED);
        CartView v = new CartView(List.of(), List.of(dead), 0L, 0L, 0, 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invalid = (List<Map<String, Object>>) wire(v).get("invalidLines");
        assertThat(invalid).hasSize(1);
        assertThat(invalid.get(0).get("selected"))
                .as("勾着的下架行要照实说它勾着 —— 改写成 false 会让用户以为自己没勾过")
                .isEqualTo(true);
        // 但它绝不计入选中合计：
        assertThat(wire(v).get("selectedSubtotal")).isEqualTo(0L);
        assertThat(wire(v).get("selectedCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("NON_NULL：可空字段缺省即省略，selected 是原始类型恒下发")
    void nullablesAreOmittedButSelectedIsAlwaysPresent() {
        // SKU 被物理删除等异常路径：商品侧字段全空。
        CartView.CartLine sparse = new CartView.CartLine("sku-x", null, null, "3 kg", 0L, 1,
                null, null, null, null, null, false);

        Map<String, Object> m = wire(sparse);
        assertThat(m).doesNotContainKey("productToken");
        assertThat(m).doesNotContainKey("invalidReason");
        assertThat(m)
                .as("selected 是 boolean 原始类型，NON_NULL 不会省略它 —— App 侧可以无条件读")
                .containsEntry("selected", false);
    }

    @Test
    @DisplayName("两个兼容构造器补的默认值都是 selected=true（等同列的 DEFAULT TRUE）")
    void legacyConstructorsDefaultToSelected() {
        var nineArg = new CartView.CartLine("s", "p", "n", "spec", 1L, 1, null, 1L, null);
        var elevenArg = new CartView.CartLine("s", "p", "n", "spec", 1L, 1, null, 1L, null,
                "SHOP_HOME", "TRIGGER_CARD");

        // 老调用点（测试与后台组装）按旧签名构造的行必须是选中态，
        // 否则它们会凭空变成「用户取消了勾选」，结算时直接漏单。
        assertThat(nineArg.selected()).isTrue();
        assertThat(elevenArg.selected()).isTrue();
    }
}
