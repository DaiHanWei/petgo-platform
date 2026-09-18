package com.tailtopia.shop.order.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：汇总提醒的消息文本（Story 3-4 AC5 · SHOP-NFR-01）。
 *
 * <p>🔒🔒 <b>本类的全部重点是「这条消息里不许有个人信息」。</b>
 * 它会落进一个 Lark 群 —— 群里的人可能远多于该看订单 PII 的人，
 * 而且 Lark 的聊天记录不受我们的留存策略管。
 *
 * <p>🎯 <b>变异靶子</b>：{@link #neverContainsAnyPersonalInformation()} 必须在
 * {@code render} 拼上收件人姓名 / 电话 / 地址中任意一项后变红。
 */
class ShopOrderNotifyMessageTest {

    private static final List<ShopOrderNotifyMessage.Line> THREE = List.of(
            new ShopOrderNotifyMessage.Line("TOKO-20260916-000042", 285_000L, 2),
            new ShopOrderNotifyMessage.Line("TOKO-20260916-000043", 100_000L, 1),
            new ShopOrderNotifyMessage.Line("TOKO-20260916-000044", 1_250_000L, 7));

    @Test
    @DisplayName("三笔订单渲染成**一条**消息，三个订单号都在")
    void threeOrdersRenderAsOneMessage() {
        String text = ShopOrderNotifyMessage.render(THREE);

        assertThat(text).contains("TOKO-20260916-000042")
                .contains("TOKO-20260916-000043")
                .contains("TOKO-20260916-000044");
        assertThat(text).contains("3 笔");
    }

    @Test
    @DisplayName("金额按千分位渲染（与后台展示口径一致）")
    void amountsAreThousandSeparated() {
        String text = ShopOrderNotifyMessage.render(THREE);

        assertThat(text).contains("Rp 285.000")
                .contains("Rp 100.000")
                .contains("Rp 1.250.000");
    }

    @Test
    @DisplayName("件数是件数不是种类数")
    void itemCountIsRendered() {
        assertThat(ShopOrderNotifyMessage.render(
                List.of(new ShopOrderNotifyMessage.Line("TOKO-1", 1000L, 7))))
                .contains("7 件");
    }

    // ---------- 🎯 PII 红线 ----------

    @Test
    @DisplayName("🎯 逐字段断言：消息里不出现任何收件人 / 下单人信息")
    void neverContainsAnyPersonalInformation() {
        // 这些值一个都不许出现在消息里。渲染函数根本拿不到它们 ——
        // 它只收 (displayNo, totalAmount, itemCount) 三字段的 Line，这是刻意的设计。
        Map<String, String> banned = Map.of(
                "receiverName", "Budi Santoso",
                "receiverPhone", "081234567890",
                "addressLine", "Jl. Melati No. 1 RT 05",
                "kotaKabupaten", "Jakarta Selatan",
                "kodePos", "12110",
                "nickname", "budi_ganteng",
                "email", "budi@example.com",
                "userId", "90210");

        String text = ShopOrderNotifyMessage.render(THREE);

        for (Map.Entry<String, String> e : banned.entrySet()) {
            assertThat(text)
                    .as("消息里出现了 %s = \"%s\" —— 它会进 Lark 群的聊天记录（SHOP-NFR-01）",
                            e.getKey(), e.getValue())
                    .doesNotContain(e.getValue());
        }
    }

    @Test
    @DisplayName("🔒 每行只有三段：订单号 | 金额 | 件数")
    void eachLineHasExactlyThreeSegments() {
        String text = ShopOrderNotifyMessage.render(
                List.of(new ShopOrderNotifyMessage.Line("TOKO-1", 1000L, 2)));

        String orderLine = text.lines().filter(l -> l.startsWith("· ")).findFirst().orElseThrow();
        assertThat(orderLine.split("\\|"))
                .as("多出一段就说明有人往里加了字段 —— 而最可能被加的是收件人")
                .hasSize(3);
    }
}
