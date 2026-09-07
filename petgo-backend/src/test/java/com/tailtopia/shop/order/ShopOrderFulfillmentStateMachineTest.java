package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.CompletionSource;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShipmentStatus;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：履约段状态机与自动确认收货（Story 4.1，SPEC-2 / SPEC-5 / S-1 / S-2 / FR-102）。
 *
 * <p>🔴 本类看的是<b>「订单不会卡死」</b>：SPEC-2 的三条出口各自都要能让订单脱离
 * {@code SHIPPED}，而「签收时刻」必须在每条路径上都被写下 —— 少写一次，Epic 5 的退货窗口
 * 就有一批订单算不出起点。
 */
class ShopOrderFulfillmentStateMachineTest {

    private static AddressSnapshot addr() {
        return new AddressSnapshot("Budi", "+628123456789", "DKI Jakarta",
                "Jakarta Selatan", "Kebayoran Baru", "Jl. Melawai IV No. 12", "12160");
    }

    /** 已付款待发货的订单。 */
    private static ShopOrder paidOrder() {
        ShopOrder o = ShopOrder.place("tok", 1L, 285_000L, 20_000L, 0L, addr());
        o.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);
        return o;
    }

    // ---------- 三条边补齐 ----------

    @Test
    @DisplayName("履约段三条边全通：待发货 → 已发货 → 已送达 → 已完成")
    void fulfillmentPathIsComplete() {
        ShopOrder o = paidOrder();
        Instant t0 = Instant.now();

        o.markShipped(t0);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        assertThat(o.getShippedAt()).isEqualTo(t0);

        Instant t1 = t0.plus(2, ChronoUnit.DAYS);
        o.markDelivered(t1, DeliverySource.SHIPMENTS_ALL_DELIVERED);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(o.getDeliveredAt()).isEqualTo(t1);

        Instant t2 = t1.plus(1, ChronoUnit.DAYS);
        o.markCompleted(t2, CompletionSource.USER_CONFIRM);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(o.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("🔴 不开 SHIPPED → COMPLETED 直达边 —— 直达会造出没有签收时刻的已完成订单")
    void noDirectShippedToCompleted() {
        assertThat(ShopOrderStatus.SHIPPED.canTransitionTo(ShopOrderStatus.COMPLETED))
                .as("直达会让 Epic 5 的退货窗口算不出起点").isFalse();

        ShopOrder o = paidOrder();
        o.markShipped(Instant.now());
        assertThatThrownBy(() -> o.transitionTo(ShopOrderStatus.COMPLETED))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("🔴 履约段的边与退款段互不越界（退款边由 Story 5.1 开放，见 SPEC-6）")
    void fulfillmentAndRefundEdgesDoNotOverlap() {
        // 🔴 SPEC-6 ①【拒收】：已发货态可整单进入退款；这是 Epic 5 开的边，不是履约段的。
        assertThat(ShopOrderStatus.SHIPPED.canTransitionTo(ShopOrderStatus.REFUNDING)).isTrue();
        // 🔴 已送达 / 已完成不走 REFUNDING：行级部分退货【不动订单主状态】（AD-5），
        //    全部行退完时直接回写 REFUNDED。开这条边会让退了一行的订单显示成整单退款中。
        assertThat(ShopOrderStatus.DELIVERED.canTransitionTo(ShopOrderStatus.REFUNDING)).isFalse();
        assertThat(ShopOrderStatus.COMPLETED.canTransitionTo(ShopOrderStatus.REFUNDING)).isFalse();
        assertThat(ShopOrderStatus.COMPLETED.canTransitionTo(ShopOrderStatus.REFUNDED)).isTrue();
    }

    @Test
    @DisplayName("已付款订单可被取消（Story 4.4 异常订单出口），已发货则不可")
    void paidOrderHasCancelExit() {
        assertThat(ShopOrderStatus.PENDING_SHIPMENT.canTransitionTo(ShopOrderStatus.CANCELLED))
                .isTrue();
        assertThat(ShopOrderStatus.SHIPPED.canTransitionTo(ShopOrderStatus.CANCELLED))
                .as("货已出门，出口是退货不是取消").isFalse();
    }

    // ---------- 🔴 SPEC-2 三条出口 ----------

    @Test
    @DisplayName("🔴 SPEC-2 出口①：后台标记 —— 可达 DELIVERED 且留痕 ADMIN_MARK")
    void exitOneAdminMark() {
        ShopOrder o = paidOrder();
        o.markShipped(Instant.now());
        o.markDelivered(Instant.now(), DeliverySource.ADMIN_MARK);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(o.getDeliverySource()).isEqualTo(DeliverySource.ADMIN_MARK);
    }

    @Test
    @DisplayName("🔴 SPEC-2 出口②：用户在已发货态确认 —— 途经 DELIVERED 写下签收时刻")
    void exitTwoUserConfirm() {
        ShopOrder o = paidOrder();
        o.markShipped(Instant.now());
        Instant now = Instant.now();
        o.markDelivered(now, DeliverySource.USER_CONFIRM);
        o.markCompleted(now, CompletionSource.USER_CONFIRM);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(o.getDeliveredAt()).as("🔴 签收时刻必须被写下，否则退货窗口无起点").isNotNull();
        assertThat(o.getDeliverySource()).isEqualTo(DeliverySource.USER_CONFIRM);
    }

    @Test
    @DisplayName("🔴 SPEC-2 出口③：发货起 M=7 日无标记则到期，第 6 天不到期")
    void exitThreeAutoDeliverAfterSevenDays() {
        ShopOrder o = paidOrder();
        Instant shipped = Instant.parse("2026-08-01T00:00:00Z");
        o.markShipped(shipped);

        assertThat(o.isAutoDeliverDue(shipped.plus(6, ChronoUnit.DAYS)))
                .as("第 6 天不该自动置送达").isFalse();
        assertThat(o.isAutoDeliverDue(shipped.plus(7, ChronoUnit.DAYS)))
                .as("整 7 日尚未超过，不到期").isFalse();
        assertThat(o.isAutoDeliverDue(shipped.plus(7, ChronoUnit.DAYS).plusSeconds(1)))
                .as("超过 7 日才到期").isTrue();
    }

    @Test
    @DisplayName("🔴 三条出口互不依赖 —— 各自都能单独让订单脱离 SHIPPED")
    void allThreeExitsIndependentlyLeaveShipped() {
        for (DeliverySource source : new DeliverySource[] {
            DeliverySource.ADMIN_MARK, DeliverySource.USER_CONFIRM, DeliverySource.AUTO_TIMEOUT,
        }) {
            ShopOrder o = paidOrder();
            o.markShipped(Instant.now());
            o.markDelivered(Instant.now(), source);
            assertThat(o.getStatus())
                    .as("出口 %s 未能让订单脱离 SHIPPED —— 缺任何一条都会死锁", source)
                    .isEqualTo(ShopOrderStatus.DELIVERED);
        }
    }

    // ---------- 自动确认收货与退货窗口并存 ----------

    @Test
    @DisplayName("送达起 7 日未确认 → 自动完成到期判定")
    void autoCompleteAfterSevenDays() {
        ShopOrder o = paidOrder();
        Instant shipped = Instant.parse("2026-08-01T00:00:00Z");
        o.markShipped(shipped);
        Instant delivered = shipped.plus(2, ChronoUnit.DAYS);
        o.markDelivered(delivered, DeliverySource.ADMIN_MARK);

        assertThat(o.isAutoCompleteDue(delivered.plus(6, ChronoUnit.DAYS))).isFalse();
        assertThat(o.isAutoCompleteDue(delivered.plus(7, ChronoUnit.DAYS).plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("🔴 自动确认后退货窗口仍在 —— 「已完成」不等于「不能再退」")
    void returnWindowSurvivesAutoCompletion() {
        ShopOrder o = paidOrder();
        Instant delivered = Instant.parse("2026-08-01T00:00:00Z");
        o.markShipped(delivered.minus(2, ChronoUnit.DAYS));
        o.markDelivered(delivered, DeliverySource.AUTO_TIMEOUT);

        Instant autoAt = delivered.plus(7, ChronoUnit.DAYS).plusSeconds(1);
        o.markCompleted(autoAt, CompletionSource.AUTO_TIMEOUT);

        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(o.getDeliveredAt()).as("🔴 自动确认不得清空签收时刻").isEqualTo(delivered);
        assertThat(o.returnWindowEndsAt()).isEqualTo(delivered.plus(7, ChronoUnit.DAYS));
        // 最坏路径：D7 置 DELIVERED → D14 置 COMPLETED，退货窗口 D7–D14 —— 自动完成的那一刻仍在窗内
        assertThat(o.isWithinReturnWindow(delivered.plus(7, ChronoUnit.DAYS))).isTrue();
        assertThat(o.isWithinReturnWindow(delivered.plus(8, ChronoUnit.DAYS))).isFalse();
    }

    @Test
    @DisplayName("🔴 SPEC-5：未签收的订单没有退货窗口起点")
    void noReturnWindowBeforeDelivery() {
        ShopOrder o = paidOrder();
        o.markShipped(Instant.now());
        assertThat(o.returnWindowEndsAt()).isNull();
        assertThat(o.isWithinReturnWindow(Instant.now())).isFalse();
    }

    @Test
    @DisplayName("🔴 签收时刻只写一次 —— 重复标记不得把退货窗口往后挪")
    void deliveredAtIsWrittenOnce() {
        ShopOrder o = paidOrder();
        Instant first = Instant.parse("2026-08-01T00:00:00Z");
        o.markShipped(first.minus(1, ChronoUnit.DAYS));
        o.markDelivered(first, DeliverySource.ADMIN_MARK);
        o.markDelivered(first.plus(3, ChronoUnit.DAYS), DeliverySource.USER_CONFIRM);

        assertThat(o.getDeliveredAt()).isEqualTo(first);
        assertThat(o.getDeliverySource()).isEqualTo(DeliverySource.ADMIN_MARK);
        assertThat(o.returnWindowEndsAt()).isEqualTo(first.plus(7, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("一单多包：第二个包裹不改写订单发货时刻（否则兜底会被无限推后）")
    void secondShipmentDoesNotResetShippedAt() {
        ShopOrder o = paidOrder();
        Instant first = Instant.parse("2026-08-01T00:00:00Z");
        o.markShipped(first);
        o.markShipped(first.plus(2, ChronoUnit.DAYS));
        assertThat(o.getShippedAt()).isEqualTo(first);
    }

    // ---------- 包裹 ----------

    @Test
    @DisplayName("包裹初态为已发出；标记送达写入时刻且幂等")
    void shipmentLifecycle() {
        Shipment s = Shipment.ship(1L, Carrier.JNE, " JP1234567890 ", 15_000L);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        assertThat(s.getTrackingNo()).isEqualTo("JP1234567890");
        assertThat(s.isDelivered()).isFalse();

        Instant at = Instant.parse("2026-08-03T00:00:00Z");
        s.markDelivered(at);
        s.markDelivered(at.plus(1, ChronoUnit.DAYS));   // 幂等，不改写
        assertThat(s.getDeliveredAt()).isEqualTo(at);
        assertThat(s.isDelivered()).isTrue();
    }

    @Test
    @DisplayName("物流单号必填、承运成本不得为负")
    void shipmentValidation() {
        assertThatThrownBy(() -> Shipment.ship(1L, Carrier.JNE, "  ", 0L))
                .isInstanceOf(AppException.class).hasMessageContaining("物流单号");
        assertThatThrownBy(() -> Shipment.ship(1L, Carrier.JNE, "JP1", -1L))
                .isInstanceOf(AppException.class).hasMessageContaining("承运成本");
    }

    @Test
    @DisplayName("🔒 包裹 toString 只含单号与承运商（非 PII），本类不持有姓名/电话/地址")
    void shipmentToStringHasNoPii() {
        String s = Shipment.ship(1L, Carrier.SICEPAT, "SC999", 0L).toString();
        assertThat(s).contains("SC999").contains("SICEPAT");
        assertThat(s).doesNotContain("Budi").doesNotContain("+628123456789");
    }

    // ---------- 承运商 ----------

    @Test
    @DisplayName("承运商三选一；未知值抛错而不是默认到某一家")
    void carrierParsing() {
        assertThat(Carrier.values()).containsExactly(Carrier.JNE, Carrier.SICEPAT,
                Carrier.ANTERAJA);
        assertThat(Carrier.parse("sicepat")).isEqualTo(Carrier.SICEPAT);
        assertThat(Carrier.parse(" ANTERAJA ")).isEqualTo(Carrier.ANTERAJA);
        assertThatThrownBy(() -> Carrier.parse("GoSend"))
                .isInstanceOf(AppException.class).hasMessageContaining("JNE");
        assertThatThrownBy(() -> Carrier.parse(null)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("每家承运商都有官网查询地址（FR-103：只跳转，不接 API）")
    void everyCarrierHasTrackingUrl() {
        for (Carrier c : Carrier.values()) {
            assertThat(c.trackingUrl()).startsWith("https://");
            assertThat(c.displayName()).isNotBlank();
        }
    }
}
