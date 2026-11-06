package com.tailtopia.shop.stag;

import com.tailtopia.admin.shared.StagOnly;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.logistics.biteship.BiteshipStubScript;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shop.logistics.BiteshipShipmentService;
import com.tailtopia.shop.logistics.BiteshipTrackingService;
import com.tailtopia.shop.logistics.WaybillResult;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShipmentRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.dto.ShopOrderRefundView;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.RefundScope;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.order.service.LatePaymentService;
import com.tailtopia.shop.order.service.ReorderOutcome;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 取消退款的 stag 测试入口（v1.4.2 shop Story 4.1 AC8，架构 D-24 / S-2 / C-1）。
 *
 * <p>🔴 {@link StagOnly}（{@code @Profile("stag")}）：生产不注册。挂在后台链 {@code /admin/stag/shop/**}
 * （会话登录 + {@code SUPER_ADMIN}，与 {@link ShopStagCouponController} 同；该前缀免 CSRF）。
 *
 * <p>只做 {@link ShopOrderRefundService} 的薄包装，不写业务逻辑；操作人传 null。
 * {@code lineId} 为内部订单行 id —— stag 内部工具例外，不对 App 暴露。
 * 「入库确认」「标记打款失败」两个入口超出 D-24 原列表，为 10.1 验收 S-8 与 F25 所需（见 story「待确认」5）。
 *
 * <p>v1.4.2 shop Story 4.2 AC8：追加迟到款「重新下单」（D-24 原列）与「迟到款退款」（D-24 未列，10.1「另一例退款」需要）
 * 两个入口，薄包装 {@link LatePaymentService}。造迟到款用现有支付模拟对已关单订单的意图模拟到账（走 {@code applyResult}）。
 *
 * <p>v1.4.2 shop Story 5.1 AC10.5：追加「下运单」（薄包装 {@link BiteshipShipmentService#createWaybill}）与「推进 stub 状态」
 * （仅 {@code BITESHIP_MODE=stub} 时可用：{@link BiteshipStubScript#setStatus} 后立即回查推进），供 App story 与 stag 验收造数。
 */
@RestController
@StagOnly
public class ShopStagController {

    private static final String AUTH = "hasRole('SUPER_ADMIN')";

    private final ShopOrderRefundService refunds;
    private final LatePaymentService latePayments;
    private final BiteshipShipmentService waybills;
    private final BiteshipTrackingService tracking;
    private final ShopOrderRepository orders;
    private final ShipmentRepository shipments;
    private final ObjectProvider<BiteshipStubScript> stubScript;

    public ShopStagController(ShopOrderRefundService refunds, LatePaymentService latePayments,
            BiteshipShipmentService waybills, BiteshipTrackingService tracking, ShopOrderRepository orders,
            ShipmentRepository shipments, ObjectProvider<BiteshipStubScript> stubScript) {
        this.refunds = refunds;
        this.latePayments = latePayments;
        this.waybills = waybills;
        this.tracking = tracking;
        this.orders = orders;
        this.shipments = shipments;
        this.stubScript = stubScript;
    }

    public record StubStatusRequest(String status) {
    }

    /** 推进 stub 状态的结果：运单原始状态 / 用户侧状态 / 订单状态。 */
    public record StubStatusResponse(String providerStatus, String userStatusKey, String orderStatus) {
    }

    @PostMapping("/admin/stag/shop/orders/{token}/waybill")
    @PreAuthorize(AUTH)
    public WaybillResult createWaybill(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token) {
        return waybills.createWaybill(requireOrder(token).getId(), admin.getAdminAccountId());
    }

    @PostMapping("/admin/stag/shop/orders/{token}/waybill/stub-status")
    @PreAuthorize(AUTH)
    public StubStatusResponse stubStatus(@PathVariable String token, @RequestBody StubStatusRequest req) {
        BiteshipStubScript script = stubScript.getIfAvailable();
        if (script == null) {
            throw AppException.conflict("仅 BITESHIP_MODE=stub 时可用");
        }
        if (req == null || req.status() == null || req.status().isBlank()) {
            throw AppException.validation("status 必填");
        }
        ShopOrder order = requireOrder(token);
        Shipment s = shipments.findActiveByShopOrderId(order.getId()).stream().filter(Shipment::isBiteship)
                .findFirst().orElseThrow(() -> AppException.conflict("该订单没有活动的 Biteship 运单"));
        script.setStatus(s.getBiteshipOrderId(), req.status().trim());
        tracking.refresh(s.getId());
        Shipment after = shipments.findById(s.getId()).orElseThrow();
        return new StubStatusResponse(after.getProviderStatus(), after.getUserStatusKey(),
                requireOrder(token).getStatus().name());
    }

    private ShopOrder requireOrder(String token) {
        return orders.findByPublicToken(token).orElseThrow(() -> AppException.notFound("订单不存在"));
    }

    /** 迟到款退款结果：退款单 token 与金额（现金 = 迟到款、币 0）。 */
    public record LateRefundResponse(String refundToken, long cashRefund, String status) {
    }

    public record CancelRequest(RefundScope scope, CancelReason reason, Long lineId, Integer qty,
            Long negotiatedAmount, String note) {
    }

    /** 取消结果：退款单 token（V1.4.2 shop-admin 5.1 起各原因整单取消都出退款单；USER_FAULT_RETURNED 须先按行入库）与订单状态。 */
    public record CancelResponse(String refundToken, String orderStatus) {
    }

    public record PayoutFailedRequest(String note) {
    }

    @PostMapping("/admin/stag/shop/orders/{token}/cancel")
    @PreAuthorize(AUTH)
    public CancelResponse cancel(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestBody CancelRequest req) {
        long actor = admin.getAdminAccountId();
        if (req == null || req.scope() == null) {
            throw AppException.validation("scope 必填");
        }
        ShopOrderRefundService.Outcome out = switch (req.scope()) {
            case WHOLE -> refunds.cancelWhole(token, requireReason(req), req.negotiatedAmount(), req.note(), actor);
            case LINE -> {
                if (req.lineId() == null || req.qty() == null) {
                    throw AppException.validation("LINE 须带 lineId 与 qty");
                }
                yield refunds.cancelLine(token, req.lineId(), req.qty(), requireReason(req), req.note(), actor);
            }
            case COMPENSATION -> refunds.compensate(token, req.negotiatedAmount(), req.note(), actor);
        };
        ShopOrderRefund r = out.refund();
        return new CancelResponse(r == null ? null : r.getPublicToken(), out.order().getStatus().name());
    }

    /** V1.4.2 shop-admin 5.1（O-13）：按行确认入库请求体 {@code {"lines": {"<lineId>": <件数>}}}。 */
    public record ReturnInboundRequest(java.util.Map<Long, Integer> lines) {
    }

    /** 按行入库结果：订单状态与是否已全部收齐（收齐后才能 USER_FAULT_RETURNED 整单取消出退款单）。 */
    public record ReturnInboundResponse(String orderStatus, boolean complete) {
    }

    @PostMapping("/admin/stag/shop/orders/{token}/return-inbound")
    @PreAuthorize(AUTH)
    public ReturnInboundResponse returnInbound(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestBody(required = false) ReturnInboundRequest req) {
        ShopOrderRefundService.InboundOutcome out = refunds.confirmReturnedInbound(token,
                req == null ? null : req.lines(), admin.getAdminAccountId());
        return new ReturnInboundResponse(out.order().getStatus().name(), out.complete());
    }

    @PostMapping("/admin/stag/shop/refunds/{token}/paid-out")
    @PreAuthorize(AUTH)
    public ShopOrderRefundView paidOut(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token) {
        return refunds.markPaidOut(token, admin.getAdminAccountId());
    }

    @PostMapping("/admin/stag/shop/refunds/{token}/payout-failed")
    @PreAuthorize(AUTH)
    public ShopOrderRefundView payoutFailed(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestBody(required = false) PayoutFailedRequest req) {
        return refunds.markPayoutFailed(token, req == null ? null : req.note(), admin.getAdminAccountId());
    }

    @PostMapping("/admin/stag/shop/orders/{token}/reorder")
    @PreAuthorize(AUTH)
    public ReorderOutcome reorder(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token) {
        return latePayments.reorderFromLatePayment(token, admin.getAdminAccountId());
    }

    @PostMapping("/admin/stag/shop/orders/{token}/late-refund")
    @PreAuthorize(AUTH)
    public LateRefundResponse lateRefund(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token) {
        ShopOrderRefund r = latePayments.refundLatePayment(token, admin.getAdminAccountId());
        return new LateRefundResponse(r.getPublicToken(), r.getCashRefund(), r.getStatus().name());
    }

    private static CancelReason requireReason(CancelRequest req) {
        if (req.reason() == null) {
            throw AppException.validation("reason 必填");
        }
        return req.reason();
    }
}
