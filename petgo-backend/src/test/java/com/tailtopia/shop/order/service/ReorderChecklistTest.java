package com.tailtopia.shop.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.order.service.ReorderChecklist.CouponState;
import com.tailtopia.shop.order.service.ReorderChecklist.LineNeed;
import com.tailtopia.shop.order.service.ReorderChecklist.LineStock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 shop-admin Story 7.2 · AD-11 · AC2 ~ AC4）：迟到款重新下单核对清单与逐行库存分配。 */
class ReorderChecklistTest {

    private static final List<LineNeed> TWO_LINES = List.of(new LineNeed(1, 10, "Royal Canin", 2),
            new LineNeed(2, 20, "Whiskas", 1));

    @Test
    void allSystemItemsMetButManualItemsGateSubmit() {
        var lines = ReorderChecklist.allocate(TWO_LINES, Map.of(10L, 5L, 20L, 1L));
        var r = ReorderChecklist.evaluate(List.of(new CouponState("uc1", true, false)), 50_000, 80_000, lines,
                false, true);
        assertThat(r.systemOk()).isTrue();
        assertThat(r.allOk()).as("用户已同意未勾").isFalse();
        assertThat(ReorderChecklist.evaluate(r.coupons(), 50_000, 80_000, lines, true, true).allOk()).isTrue();
    }

    @Test
    void expiredCouponIsFineButLockedOrUsedIsNot() {
        var lines = ReorderChecklist.allocate(TWO_LINES, Map.of(10L, 5L, 20L, 5L));
        assertThat(ReorderChecklist.evaluate(List.of(new CouponState("uc1", true, true)), 0, 0, lines, true, true)
                .couponsOk()).as("AC3：已过期可以").isTrue();
        assertThat(ReorderChecklist.evaluate(List.of(new CouponState("uc1", false, false)), 0, 0, lines, true, true)
                .allOk()).as("AC3：被占用 / 用掉 / 作废不能提交").isFalse();
    }

    @Test
    void coinUsesActualRequiredAmount() {
        var lines = ReorderChecklist.allocate(TWO_LINES, Map.of(10L, 5L, 20L, 5L));
        assertThat(ReorderChecklist.evaluate(List.of(), 50_000, 49_999, lines, true, true).coinOk()).isFalse();
        assertThat(ReorderChecklist.evaluate(List.of(), 50_000, 50_000, lines, true, true).coinOk()).isTrue();
        assertThat(ReorderChecklist.evaluate(List.of(), 0, 0, lines, true, true).coinOk()).as("无币段").isTrue();
    }

    @Test
    void partialStockSubmittableAllShortIsNot() {
        var partial = ReorderChecklist.allocate(TWO_LINES, Map.of(10L, 5L, 20L, 0L));
        var r = ReorderChecklist.evaluate(List.of(), 0, 0, partial, true, true);
        assertThat(r.allOk()).as("AC4：部分缺货可提交").isTrue();
        assertThat(r.shortLines()).extracting(LineStock::lineId).containsExactly(2L);
        var none = ReorderChecklist.allocate(TWO_LINES, Map.of(10L, 1L));
        assertThat(ReorderChecklist.evaluate(List.of(), 0, 0, none, true, true).anyInStock())
                .as("AC4：全部缺货只能退款").isFalse();
    }

    @Test
    void sameSkuLinesShareAvailabilityInOrder() {
        var lines = ReorderChecklist.allocate(List.of(new LineNeed(1, 10, "A", 2), new LineNeed(2, 10, "A", 2)),
                Map.of(10L, 3L));
        assertThat(lines).extracting(LineStock::locks).containsExactly(true, false);
    }

    @Test
    void shortageNoteFitsTwoHundredChars() {
        List<LineStock> many = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> new LineStock(i, i, "Produk dengan nama panjang " + i, 1, false)).toList();
        String note = ReorderChecklist.shortageNote(many);
        assertThat(note.length()).isLessThanOrEqualTo(200);
        assertThat(note).contains("等 40 行");
        assertThat(ReorderChecklist.shortageNote(List.of(new LineStock(1, 1, "Whiskas", 2, false))))
                .isEqualTo("迟到款重新下单时缺货：Whiskas ×2");
    }
}
