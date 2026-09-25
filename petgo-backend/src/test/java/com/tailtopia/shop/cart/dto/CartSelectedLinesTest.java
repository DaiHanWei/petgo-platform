package com.tailtopia.shop.cart.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：{@link CartView#selectedLines()} 的过滤条件（Story 4-1 AC4 / AC6 · SD-6）。
 *
 * <p>🎯 <b>变异靶子</b>：本类把「preview 与 placeOrder 到底拿哪几行去算钱」这件事钉在 L0。
 * 去掉 {@code selectedLines()} 里的 {@code .filter(CartLine::selected)}，本类必须变红 ——
 * 而在此之前，这条护栏只有 L1（{@code CheckoutIntegrationTest} 的「三件选两件」）能测，
 * L1 在云端跑不了，等于合并前没人能验证它。
 *
 * <p>为什么这条不能漂：后端不按选中集算，而 App 上画着勾选框 ——
 * 用户勾了两件、付了三件的钱。那是<b>能造成资损的谎</b>，不是显示问题。
 *
 * <p><b>这一个过滤条件被三个调用点共用</b>（{@code CheckoutService.preview} /
 * {@code placeOrder} / {@code CheckoutPreviewView.of}），所以钉住它就等于钉住三处。
 *
 * <p>纯函数，无 Spring 上下文、无 DB。
 */
class CartSelectedLinesTest {

    private static CartView.CartLine line(String token, long price, int qty, boolean selected,
            String invalidReason) {
        return new CartView.CartLine(token, "p-" + token, "Royal Canin", "3 kg", price, qty,
                null, 10L, invalidReason, null, null, selected);
    }

    @Test
    @DisplayName("🎯 只取勾选的行，合计是这些行的 price*qty 之和")
    void takesOnlySelectedLines() {
        CartView cart = new CartView(
                List.of(line("a", 100_000L, 2, true, null),      // 200.000
                        line("b", 50_000L, 1, true, null),       //  50.000
                        line("c", 30_000L, 3, false, null)),     //  90.000（没勾）
                List.of(), 340_000L, 250_000L, 6, 3);

        var selected = cart.selectedLines();

        assertThat(selected).extracting(CartView.CartLine::skuToken)
                .as("🎯 去掉 selected 过滤，这条会变成 [a, b, c]")
                .containsExactly("a", "b");
        // 配套金额是 CartView.selectedSubtotal()（由 CartService.view 在同一循环里算），
        // 去掉过滤后订单会按 340.000 建单 —— 用户勾两件、付三件的钱。
        assertThat(selected).extracting(l -> l.price() * l.qty())
                .as("🎯 去掉过滤后这里会多出 c 那 90.000")
                .containsExactly(200_000L, 50_000L);
    }

    @Test
    @DisplayName("🔴 失效行一律不在选中集里（它们根本不在 cart.lines() 中）")
    void invalidLinesNeverEnterTheSelection() {
        CartView cart = new CartView(
                List.of(line("a", 100_000L, 1, true, null)),
                // 勾着的下架行 —— 它在 invalidLines 里，进不了结算集
                List.of(line("dead", 999_000L, 1, true, CartView.REASON_DELISTED)),
                100_000L, 100_000L, 1, 1);

        assertThat(cart.selectedLines()).hasSize(1);
        assertThat(cart.selectedLines().getFirst().skuToken()).isEqualTo("a");
    }

    @Test
    @DisplayName("🔴 过滤条件读的是 cart.lines()，不是「没下架 && 没售罄」")
    void filterRidesOnValidityGroupingNotOnReasonLiterals() {
        // Epic 6 会追加第三种失效原因（停用品类，SHOP-FR-19）。
        // 到那时这一行的 invalidReason 是一个本类没见过的值，但它同样在 invalidLines 里 ——
        // 只要过滤条件靠的是「在不在 cart.lines() 里」，就自动排除，不用回来改这里。
        CartView cart = new CartView(
                List.of(line("a", 100_000L, 1, true, null)),
                List.of(line("future", 500_000L, 1, true, "CATEGORY_DISABLED")),
                100_000L, 100_000L, 1, 1);

        assertThat(cart.selectedLines()).extracting(CartView.CartLine::skuToken)
                .containsExactly("a");
    }

    @Test
    @DisplayName("一件都没勾 → 空选中集、合计为 0（调用方据此抛 422）")
    void nothingSelectedYieldsEmptySelection() {
        CartView cart = new CartView(
                List.of(line("a", 100_000L, 2, false, null),
                        line("b", 50_000L, 1, false, null)),
                List.of(), 250_000L, 0L, 3, 0);

        var selected = cart.selectedLines();

        assertThat(selected).as("调用方据此抛 422「请至少选择一件商品」").isEmpty();
    }

    @Test
    @DisplayName("🔴 老版本兼容：全选中时选中合计恒等于 subtotal")
    void legacyAllSelectedMatchesSubtotal() {
        // 老版本 App 从不调选择端点，它的每一行都是 selected=true（列 DEFAULT TRUE）。
        // 这时 selectionOf 必须是一个恒等变换 —— 否则老客户端的金额会变，而它无从察觉。
        CartView cart = new CartView(
                List.of(line("a", 100_000L, 2, true, null),
                        line("b", 50_000L, 3, true, null)),
                List.of(), 350_000L, 350_000L, 5, 5);

        var selected = cart.selectedLines();

        assertThat(selected).as("全选中时这是一个恒等变换").hasSize(2);
        assertThat(cart.selectedSubtotal()).isEqualTo(cart.subtotal());
    }

    @Test
    @DisplayName("空车 → 空选中集，不抛")
    void emptyCartIsSafe() {
        CartView cart = new CartView(List.of(), List.of(), 0L, 0L, 0, 0);

        assertThat(cart.selectedLines()).isEmpty();
    }
}
