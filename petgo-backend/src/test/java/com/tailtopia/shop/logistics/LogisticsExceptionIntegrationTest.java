package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shared.logistics.biteship.BiteshipStubScript;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShipmentStatus;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.ShopOrderDetailView;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：v1.4.2 shop Story 5.2 —— 停滞检测 / 自动清除、自动送达排除与重新计时、揽收失败三种处置、退回确认与重发、丢件破损、
 * 确认收货清标记（stub 模式）。🔴 需 Docker postgres + redis，云端不跑（待本地验收）。
 */
@TestPropertySource(properties = {"petgo.shop.sku-cap=500", "petgo.shop.biteship.mode=stub"})
class LogisticsExceptionIntegrationTest extends BiteshipIntegrationSupport {

    @Autowired
    private BiteshipShipmentService waybills;
    @Autowired
    private BiteshipTrackingService tracking;
    @Autowired
    private BiteshipStubScript stub;
    @Autowired
    private ShipmentRepository shipments;
    @Autowired
    private ShopOrderFulfillmentService fulfillment;
    @Autowired
    private ShopOrderRefundService refunds;
    @Autowired
    private LogisticsStallJob stallJob;
    @Autowired
    private ShopLogisticsExceptionService service;

    private Shipment active(long orderId) {
        return shipments.findActiveByShopOrderId(orderId).get(0);
    }

    private ShopOrderDetailView.Logistics logistics(ShopOrder o) {
        return ShopOrderDetailView.Logistics.of(o, shipments.findByShopOrderIdOrderByIdAsc(o.getId()));
    }

    /** 下运单并推到 picked（订单 SHIPPED）。 */
    private ShopOrder shippedViaBiteship(long uid) {
        ShopOrder o = paidBiteshipOrder(uid);
        waybills.createWaybill(o.getId(), ACTOR);
        Shipment s = active(o.getId());
        stub.setStatus(s.getBiteshipOrderId(), "picked");
        tracking.refresh(s.getId());
        return reload(o.getPublicToken());
    }

    @Test
    @DisplayName("provider_status_at 回拨 4 天 → 停滞任务标 STALLED（详情不显示）；stub 推进 in_transit → 清除并写 timer_resumed_at")
    void stallAndAutoClear() {
        ShopOrder o = shippedViaBiteship(seedUser());
        Shipment s = active(o.getId());
        jdbc.update("UPDATE shipments SET provider_status_at = now() - interval '4 days' WHERE id = ?", s.getId());
        assertThat(stallJob.run().flagged()).contains(o.getDisplayNo());
        ShopOrder stalled = reload(o.getPublicToken());
        assertThat(stalled.getLogisticsException()).isEqualTo(LogisticsException.STALLED);
        assertThat(logistics(stalled).exception()).isNull();

        stub.setStatus(s.getBiteshipOrderId(), "in_transit");
        tracking.refresh(s.getId());
        ShopOrder cleared = reload(o.getPublicToken());
        assertThat(cleared.getLogisticsException()).isNull();
        assertThat(cleared.getTimerResumedAt()).isNotNull();
    }

    @Test
    @DisplayName("带 STALLED 的 SHIPPED 订单回拨发货 20 天 → 自动送达不推进；清除后回拨 timer_resumed_at 15 天 → 推进")
    void autoDeliverExcludesAndRestarts() {
        ShopOrder o = shippedViaBiteship(seedUser());
        jdbc.update("UPDATE shop_orders SET logistics_exception = 'STALLED', logistics_exception_at = now(), "
                + "shipped_at = now() - interval '20 days' WHERE id = ?", o.getId());
        fulfillment.autoDeliverOverdue(100);
        assertThat(reload(o.getPublicToken()).getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);

        service.clearException(o.getId(), ACTOR);
        fulfillment.autoDeliverOverdue(100);
        assertThat(reload(o.getPublicToken()).getStatus()).as("清除于刚才 → 仍不推进")
                .isEqualTo(ShopOrderStatus.SHIPPED);

        jdbc.update("UPDATE shop_orders SET timer_resumed_at = now() - interval '15 days' WHERE id = ?", o.getId());
        fulfillment.autoDeliverOverdue(100);
        ShopOrder delivered = reload(o.getPublicToken());
        assertThat(delivered.getStatus()).isEqualTo(ShopOrderStatus.DELIVERED);
        assertThat(delivered.getDeliverySource()).isEqualTo(DeliverySource.AUTO_TIMEOUT);
    }

    /** 下运单后 courier_not_found → PICKUP_FAILED；stub 再置回可取消状态（模拟 Biteship 接受取消）。 */
    private ShopOrder pickupFailed(long uid) {
        ShopOrder o = paidBiteshipOrder(uid);
        waybills.createWaybill(o.getId(), ACTOR);
        Shipment s = active(o.getId());
        stub.setStatus(s.getBiteshipOrderId(), "courier_not_found");
        tracking.refresh(s.getId());
        ShopOrder flagged = reload(o.getPublicToken());
        assertThat(flagged.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
        stub.setStatus(s.getBiteshipOrderId(), "confirmed");
        return flagged;
    }

    @Test
    @DisplayName("揽收失败：重下运单 → 旧运单 CANCELLED、新运单 CREATED、标记清除")
    void pickupRetry() {
        ShopOrder o = pickupFailed(seedUser());
        assertThat(service.resolvePickupFailureByRetry(o.getId(), ACTOR).resolvedOk()).isTrue();
        List<Shipment> all = shipments.findByShopOrderIdOrderByIdAsc(o.getId());
        assertThat(all.get(0).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(all.get(1).getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(reload(o.getPublicToken()).getLogisticsException()).isNull();
    }

    @Test
    @DisplayName("揽收失败：转手工运单 → 订单 SHIPPED、标记清除")
    void pickupManual() {
        ShopOrder o = pickupFailed(seedUser());
        assertThat(service.resolvePickupFailureByManualWaybill(o.getId(), Carrier.JNE, "JM" + SEQ.incrementAndGet(),
                9_000L, ACTOR).resolvedOk()).isTrue();
        ShopOrder after = reload(o.getPublicToken());
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.SHIPPED);
        assertThat(after.getLogisticsException()).isNull();
    }

    @Test
    @DisplayName("揽收失败：取消订单 → REFUNDING、标记保留")
    void pickupCancel() {
        ShopOrder o = pickupFailed(seedUser());
        assertThat(service.resolvePickupFailureByCancel(o.getId(), ACTOR).resolvedOk()).isTrue();
        ShopOrder after = reload(o.getPublicToken());
        assertThat(after.getStatus()).isIn(ShopOrderStatus.REFUNDING, ShopOrderStatus.REFUNDED);
        assertThat(after.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
    }

    @Test
    @DisplayName("揽收失败：运单处于 picked（不可取消）→ 处置中止，订单与标记不动")
    void pickupAbortWhenNotCancellable() {
        ShopOrder o = pickupFailed(seedUser());
        stub.setStatus(active(o.getId()).getBiteshipOrderId(), "picked");
        assertThat(service.resolvePickupFailureByRetry(o.getId(), ACTOR).outcome())
                .isEqualTo(PickupResolution.Outcome.ABORTED);
        ShopOrder after = reload(o.getPublicToken());
        assertThat(after.getLogisticsException()).isEqualTo(LogisticsException.PICKUP_FAILED);
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
    }

    @Test
    @DisplayName("returned → RETURNED → 确认快递原因 → 详情 DELIVERY_ISSUE / RETURNED、claim_pending → 重发 → 标记清除、claim_pending 保留")
    void returnedCourierFault() {
        ShopOrder o = shippedViaBiteship(seedUser());
        Shipment s = active(o.getId());
        stub.setStatus(s.getBiteshipOrderId(), "returned");
        tracking.refresh(s.getId());
        assertThat(reload(o.getPublicToken()).getLogisticsException()).isEqualTo(LogisticsException.RETURNED);

        service.confirmReturned(o.getId(), ReturnCause.COURIER_FAULT, ACTOR);
        ShopOrder confirmed = reload(o.getPublicToken());
        assertThat(confirmed.isClaimPending()).isTrue();
        assertThat(logistics(confirmed).exception()).isEqualTo("DELIVERY_ISSUE");
        assertThat(logistics(confirmed).exceptionType()).isEqualTo("RETURNED");

        assertThat(service.reshipAfterCourierFault(o.getId(), ACTOR).created()).isTrue();
        ShopOrder after = reload(o.getPublicToken());
        assertThat(after.getLogisticsException()).isNull();
        assertThat(after.isClaimPending()).isTrue();
    }

    @Test
    @DisplayName("markLostOrDamaged → 详情配送异常；用户确认收货 → COMPLETED、标记清除、claim_pending 保留")
    void lostThenUserConfirms() {
        long uid = seedUser();
        ShopOrder o = shippedViaBiteship(uid);
        service.markLostOrDamaged(o.getId(), LogisticsException.LOST, ACTOR);
        assertThat(logistics(reload(o.getPublicToken())).exceptionType()).isEqualTo("LOST");

        fulfillment.confirmReceipt(uid, o.getPublicToken());
        ShopOrder after = reload(o.getPublicToken());
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(after.getLogisticsException()).isNull();
        assertThat(after.isClaimPending()).isTrue();
    }

    @Test
    @DisplayName("USER_FAULT 确认后调 4.1 整单取消 → REFUNDING、详情不再显示配送异常")
    void returnedUserFault() {
        ShopOrder o = shippedViaBiteship(seedUser());
        Shipment s = active(o.getId());
        stub.setStatus(s.getBiteshipOrderId(), "returned");
        tracking.refresh(s.getId());
        service.confirmReturned(o.getId(), ReturnCause.USER_FAULT, ACTOR);
        assertThat(reload(o.getPublicToken()).isClaimPending()).isFalse();

        refunds.cancelWhole(o.getPublicToken(), CancelReason.USER_FAULT_RETURNED, "用户拒收", ACTOR);
        ShopOrder after = reload(o.getPublicToken());
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
        assertThat(after.hasUserVisibleDeliveryIssue()).isFalse();
    }
}
