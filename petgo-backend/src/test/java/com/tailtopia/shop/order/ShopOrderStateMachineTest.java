package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.service.ShopTokenGenerator;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：订单状态机与不可枚举订单号（Story 3.2，🔒 安全攸关）。
 */
class ShopOrderStateMachineTest {

    private static AddressSnapshot addr() {
        return new AddressSnapshot("Budi", "+628123456789", "DKI Jakarta",
                "Jakarta Selatan", "Kebayoran Baru", "Jl. Melawai IV No. 12", "12160");
    }

    private static ShopOrder order() {
        return ShopOrder.place("tok", 1L, 285_000L, 20_000L, 0L, addr());
    }

    // ---------- 🔒 订单号不可枚举（FR-102 / AD-7） ----------

    @Test
    @DisplayName("🔒 1000 个订单号：无碰撞、无前缀规律、长度与字符集固定（Base62 22 位）")
    void orderTokensAreUnpredictable() {
        ShopTokenGenerator gen = new ShopTokenGenerator();
        Set<String> seen = new HashSet<>();
        Set<Character> firstChars = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            String t = gen.generate();
            assertThat(t).hasSize(22).matches("^[0-9a-zA-Z]{22}$");
            assertThat(seen.add(t)).as("订单号碰撞").isTrue();
            firstChars.add(t.charAt(0));
        }
        // 🔴 首字符应散布在整个字符集上。若首位固定（如都以 'T' 开头），
        //    等于把 token 空间白白缩小，也暴露了生成方式。
        assertThat(firstChars.size()).as("首字符分布过窄，疑似有前缀规律").isGreaterThan(30);
    }

    @Test
    @DisplayName("🔒 订单号不含日期串 —— TT+YYYYMMDD+序列 同样可枚举，会泄露平台当日单量")
    void orderTokenContainsNoDateOrSequencePattern() {
        ShopTokenGenerator gen = new ShopTokenGenerator();
        String today = java.time.LocalDate.now().toString().replace("-", "");
        for (int i = 0; i < 200; i++) {
            String t = gen.generate();
            assertThat(t)
                    .as("订单号里出现日期串 = 任何用户都能从自己的订单号推断平台当日单量")
                    .doesNotContain(today);
        }
    }

    // ---------- 状态机 ----------

    @Test
    @DisplayName("下单初态是 PENDING_PAYMENT")
    void placedOrderStartsPendingPayment() {
        assertThat(order().getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("Epic 3 开放的两条边：支付成功 → 待发货；取消 → 已取消")
    void epic3LegalEdges() {
        ShopOrder paid = order();
        paid.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(paid.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);

        ShopOrder cancelled = order();
        cancelled.transitionTo(ShopOrderStatus.CANCELLED);
        assertThat(cancelled.getStatus()).isEqualTo(ShopOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("🔴 非法迁移抛领域异常（待支付 → 已完成 / 已送达 都不许直跳）")
    void illegalTransitionsRejected() {
        for (ShopOrderStatus bad : new ShopOrderStatus[] {
            ShopOrderStatus.COMPLETED, ShopOrderStatus.DELIVERED,
            ShopOrderStatus.SHIPPED, ShopOrderStatus.REFUNDED,
        }) {
            ShopOrder o = order();
            assertThatThrownBy(() -> o.transitionTo(bad))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("不允许");
            assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        }
    }

    @Test
    @DisplayName("🔴 终态不可再迁移（无悬空态）")
    void terminalStatesAreFinal() {
        ShopOrder o = order();
        o.transitionTo(ShopOrderStatus.CANCELLED);
        assertThat(o.getStatus().isTerminal()).isTrue();
        assertThatThrownBy(() -> o.transitionTo(ShopOrderStatus.PENDING_SHIPMENT))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("同态迁移幂等 —— 重复投递的支付回调不该报错")
    void sameStateTransitionIsIdempotent() {
        ShopOrder o = order();
        o.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);
        o.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);   // 不抛
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }

    @Test
    @DisplayName("各 Epic 的边只在自己那一段开放（本类做存在性对照，行为细节见各 Epic 的状态机用例）")
    void edgesOpenPerEpic() {
        // Story 4.1 履约段
        assertThat(ShopOrderStatus.PENDING_SHIPMENT.canTransitionTo(ShopOrderStatus.SHIPPED))
                .as("发货边由 Story 4.1 开放").isTrue();
        // Story 5.1 退款段
        assertThat(ShopOrderStatus.REFUNDING.canTransitionTo(ShopOrderStatus.REFUNDED))
                .as("退款执行由 Story 5.1 开放").isTrue();

        // 🔴 仍然关闭的：未支付的订单没有钱可退，不该有任何退款边。
        //    开了它等于允许一笔没收过钱的订单走进退款流程。
        assertThat(ShopOrderStatus.PENDING_PAYMENT.canTransitionTo(ShopOrderStatus.REFUNDING))
                .as("未支付订单的出口是取消，不是退款").isFalse();
        assertThat(ShopOrderStatus.PENDING_PAYMENT.canTransitionTo(ShopOrderStatus.REFUNDED))
                .isFalse();
        // 🔴 终态仍不可再迁移
        assertThat(ShopOrderStatus.CANCELLED.canTransitionTo(ShopOrderStatus.REFUNDING)).isFalse();
        assertThat(ShopOrderStatus.REFUNDED.canTransitionTo(ShopOrderStatus.REFUNDING)).isFalse();
    }

    // ---------- 🔒 PII 与金额 ----------

    @Test
    @DisplayName("🔒 toString 不泄露地址快照里的 PII")
    void toStringOmitsPii() {
        String s = order().toString();
        assertThat(s).doesNotContain("Budi").doesNotContain("+628123456789")
                .doesNotContain("Jl. Melawai");
        assertThat(s).contains("tok").contains("PENDING_PAYMENT");

        // 快照 record 自身也覆盖了 toString
        assertThat(addr().toString()).doesNotContain("Budi").contains("Kebayoran Baru");
    }

    @Test
    @DisplayName("总额 = 商品小计 + 运费 + 免运抵扣（抵扣为负）")
    void totalIsSumOfLines() {
        ShopOrder free = ShopOrder.place("t", 1L, 285_000L, 20_000L, -20_000L, addr());
        assertThat(free.getTotalAmount()).isEqualTo(285_000L);

        ShopOrder paid = ShopOrder.place("t", 1L, 285_000L, 20_000L, 0L, addr());
        assertThat(paid.getTotalAmount()).isEqualTo(305_000L);
    }
}
