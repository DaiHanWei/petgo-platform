package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.CompletionSource;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.CancelReason;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** L0：v1.4.2 shop Story 5.2 AC1 / AC8 —— 物流异常写入 / 覆盖 / 确认 / 清除、claim_pending、用户侧可见判据。 */
class ShopOrderLogisticsExceptionTest {

    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    private static ShopOrder pendingShipment() {
        ShopOrder o = ShopOrder.place("tok", "TOKO-20261105-ABC123", T0, 1L, 285_000L, 20_000L, 0L,
                new AddressSnapshot("Budi", "+628123456789", "DKI Jakarta", "Jakarta Selatan", "Kebayoran Baru",
                        "Jl. Melawai IV No. 12", "12160"));
        o.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);
        return o;
    }

    private static ShopOrder shipped() {
        ShopOrder o = pendingShipment();
        o.markShipped(T0);
        return o;
    }

    // ---------- AC1.1 写入 / 覆盖 ----------

    @Test
    @DisplayName("当前为空 → 写入类型与时刻、未确认；订单状态不变")
    void flagWritesWhenEmpty() {
        ShopOrder o = shipped();
        assertThat(o.flagLogisticsException(LogisticsException.PICKUP_FAILED, T0)).isTrue();
        assertThat(o.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
        assertThat(o.getLogisticsExceptionAt()).isEqualTo(T0);
        assertThat(o.isLogisticsExceptionConfirmed()).isFalse();
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
    }

    @ParameterizedTest
    @EnumSource(value = LogisticsException.class, names = {"PICKUP_FAILED", "RETURNED", "LOST", "DAMAGED"})
    @DisplayName("拍板①：STALLED 可被更严重的异常覆盖")
    void stalledOverriddenBySevere(LogisticsException type) {
        ShopOrder o = shipped();
        o.flagLogisticsException(LogisticsException.STALLED, T0);
        assertThat(o.flagLogisticsException(type, T0.plusSeconds(60))).isTrue();
        assertThat(o.getLogisticsException()).isEqualTo(type);
        assertThat(o.getLogisticsExceptionAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    @DisplayName("STALLED 再标 STALLED → 不改写时刻")
    void stalledNotRewritten() {
        ShopOrder o = shipped();
        o.flagLogisticsException(LogisticsException.STALLED, T0);
        assertThat(o.flagLogisticsException(LogisticsException.STALLED, T0.plusSeconds(60))).isFalse();
        assertThat(o.getLogisticsExceptionAt()).isEqualTo(T0);
    }

    @ParameterizedTest
    @EnumSource(value = LogisticsException.class, names = {"PICKUP_FAILED", "RETURNED", "LOST", "DAMAGED"})
    @DisplayName("拍板①：已有非停滞异常 → 任何新类型都不覆盖（返回 false）")
    void nonStalledNotOverridden(LogisticsException existing) {
        ShopOrder o = shipped();
        o.flagLogisticsException(existing, T0);
        for (LogisticsException next : LogisticsException.values()) {
            assertThat(o.flagLogisticsException(next, T0.plusSeconds(60))).isFalse();
        }
        assertThat(o.getLogisticsException()).isEqualTo(existing);
        assertThat(o.getLogisticsExceptionAt()).isEqualTo(T0);
    }

    // ---------- AC1.2 确认 ----------

    @Test
    @DisplayName("确认 RETURNED（快递原因）→ confirmed + claim_pending；用户原因不置 claim_pending")
    void confirmReturned() {
        ShopOrder courier = shipped();
        courier.flagLogisticsException(LogisticsException.RETURNED, T0);
        courier.confirmLogisticsException(LogisticsException.RETURNED, true, T0.plusSeconds(60));
        assertThat(courier.isLogisticsExceptionConfirmed()).isTrue();
        assertThat(courier.isClaimPending()).isTrue();
        assertThat(courier.getLogisticsExceptionAt()).as("已有标记时不改写时刻").isEqualTo(T0);

        ShopOrder user = shipped();
        user.flagLogisticsException(LogisticsException.RETURNED, T0);
        user.confirmLogisticsException(LogisticsException.RETURNED, false, T0);
        assertThat(user.isLogisticsExceptionConfirmed()).isTrue();
        assertThat(user.isClaimPending()).isFalse();
    }

    @Test
    @DisplayName("当前为空 / STALLED → 运营可直接写入 LOST / DAMAGED 并确认（写入时刻 = at）")
    void confirmWritesInLostDamaged() {
        ShopOrder o = shipped();
        o.confirmLogisticsException(LogisticsException.LOST, true, T0);
        assertThat(o.getLogisticsException()).isEqualTo(LogisticsException.LOST);
        assertThat(o.getLogisticsExceptionAt()).isEqualTo(T0);
        assertThat(o.isLogisticsExceptionConfirmed()).isTrue();
        assertThat(o.isClaimPending()).isTrue();

        ShopOrder s = shipped();
        s.flagLogisticsException(LogisticsException.STALLED, T0);
        s.confirmLogisticsException(LogisticsException.DAMAGED, true, T0.plusSeconds(5));
        assertThat(s.getLogisticsException()).isEqualTo(LogisticsException.DAMAGED);
    }

    @Test
    @DisplayName("确认类型与当前异常不符 → 409；PICKUP_FAILED / STALLED 不能确认 → 422")
    void confirmRejects() {
        ShopOrder o = shipped();
        o.flagLogisticsException(LogisticsException.PICKUP_FAILED, T0);
        assertThatThrownBy(() -> o.confirmLogisticsException(LogisticsException.RETURNED, true, T0))
                .isInstanceOf(AppException.class).hasMessageContaining("PICKUP_FAILED");
        assertThatThrownBy(() -> o.confirmLogisticsException(LogisticsException.PICKUP_FAILED, false, T0))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> o.confirmLogisticsException(LogisticsException.STALLED, false, T0))
                .isInstanceOf(AppException.class);
        assertThat(o.isLogisticsExceptionConfirmed()).isFalse();
    }

    @Test
    @DisplayName("AC8.1：claimPending=false 的确认不清已有 claim_pending")
    void confirmNeverClearsClaim() {
        ShopOrder o = shipped();
        o.setClaimPending(true);
        o.flagLogisticsException(LogisticsException.RETURNED, T0);
        o.confirmLogisticsException(LogisticsException.RETURNED, false, T0);
        assertThat(o.isClaimPending()).isTrue();
    }

    // ---------- AC1.3 清除 ----------

    @Test
    @DisplayName("清除 → 类型 / 时刻清空、confirmed=false、timer_resumed_at=at；claim_pending 不动；空标记幂等 false")
    void clear() {
        ShopOrder o = shipped();
        o.confirmLogisticsException(LogisticsException.LOST, true, T0);
        Instant at = T0.plusSeconds(3600);
        assertThat(o.clearLogisticsException(at)).isTrue();
        assertThat(o.getLogisticsException()).isNull();
        assertThat(o.getLogisticsExceptionAt()).isNull();
        assertThat(o.isLogisticsExceptionConfirmed()).isFalse();
        assertThat(o.getTimerResumedAt()).isEqualTo(at);
        assertThat(o.isClaimPending()).as("已在索赔的由运营自行撤回").isTrue();

        assertThat(o.clearLogisticsException(at.plusSeconds(60))).isFalse();
        assertThat(o.getTimerResumedAt()).as("幂等：不改写").isEqualTo(at);
    }

    @Test
    @DisplayName("setClaimPending：运营置 false 是唯一清除入口")
    void setClaim() {
        ShopOrder o = shipped();
        o.setClaimPending(true);
        assertThat(o.isClaimPending()).isTrue();
        o.setClaimPending(false);
        assertThat(o.isClaimPending()).isFalse();
    }

    // ---------- AC1.6 用户侧可见判据 ----------

    @ParameterizedTest
    @EnumSource(value = LogisticsException.class, names = {"RETURNED", "LOST", "DAMAGED"})
    @DisplayName("已确认的 RETURNED / LOST / DAMAGED 在 SHIPPED / DELIVERED → 可见；未确认 → 不可见")
    void visibleOnlyWhenConfirmed(LogisticsException type) {
        ShopOrder o = shipped();
        o.flagLogisticsException(type, T0);
        assertThat(o.hasUserVisibleDeliveryIssue()).isFalse();
        o.confirmLogisticsException(type, false, T0);
        assertThat(o.hasUserVisibleDeliveryIssue()).isTrue();
        o.markDelivered(T0.plusSeconds(60), DeliverySource.ADMIN_MARK);
        assertThat(o.hasUserVisibleDeliveryIssue()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = LogisticsException.class, names = {"PICKUP_FAILED", "STALLED"})
    @DisplayName("揽收失败 / 停滞 → 用户侧永不提示")
    void pickupAndStalledNeverVisible(LogisticsException type) {
        ShopOrder o = shipped();
        o.flagLogisticsException(type, T0);
        assertThat(o.hasUserVisibleDeliveryIssue()).isFalse();
    }

    @Test
    @DisplayName("拍板④：订单进入 COMPLETED / REFUNDING 后不再显示配送异常；待发货也不显示")
    void notVisibleOutsideShippedDelivered() {
        ShopOrder completed = shipped();
        completed.markDelivered(T0, DeliverySource.ADMIN_MARK);
        completed.confirmLogisticsException(LogisticsException.DAMAGED, true, T0);
        assertThat(completed.hasUserVisibleDeliveryIssue()).isTrue();
        completed.markCompleted(T0.plusSeconds(60), CompletionSource.USER_CONFIRM);
        assertThat(completed.hasUserVisibleDeliveryIssue()).isFalse();

        ShopOrder refunding = shipped();
        refunding.confirmLogisticsException(LogisticsException.LOST, true, T0);
        refunding.markRefunding(CancelReason.LOST_DAMAGED, T0.plusSeconds(60));
        assertThat(refunding.hasUserVisibleDeliveryIssue()).isFalse();

        ShopOrder pending = pendingShipment();
        pending.confirmLogisticsException(LogisticsException.LOST, true, T0);
        assertThat(pending.hasUserVisibleDeliveryIssue()).isFalse();
    }
}
