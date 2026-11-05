package com.tailtopia.shop.stag;

import com.tailtopia.admin.shared.StagOnly;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.dto.ShopOrderRefundView;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.RefundScope;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import org.springframework.security.access.prepost.PreAuthorize;
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
 */
@RestController
@StagOnly
public class ShopStagController {

    private static final String AUTH = "hasRole('SUPER_ADMIN')";

    private final ShopOrderRefundService refunds;

    public ShopStagController(ShopOrderRefundService refunds) {
        this.refunds = refunds;
    }

    public record CancelRequest(RefundScope scope, CancelReason reason, Long lineId, Integer qty,
            Long negotiatedAmount, String note) {
    }

    /** 取消结果：退款单 token（USER_FAULT_RETURNED 整单取消无退款单，为 null）与订单状态。 */
    public record CancelResponse(String refundToken, String orderStatus) {
    }

    public record PayoutFailedRequest(String note) {
    }

    @PostMapping("/admin/stag/shop/orders/{token}/cancel")
    @PreAuthorize(AUTH)
    public CancelResponse cancel(@PathVariable String token, @RequestBody CancelRequest req) {
        if (req == null || req.scope() == null) {
            throw AppException.validation("scope 必填");
        }
        ShopOrderRefundService.Outcome out = switch (req.scope()) {
            case WHOLE -> refunds.cancelWhole(token, requireReason(req), req.negotiatedAmount(), req.note(), null);
            case LINE -> {
                if (req.lineId() == null || req.qty() == null) {
                    throw AppException.validation("LINE 须带 lineId 与 qty");
                }
                yield refunds.cancelLine(token, req.lineId(), req.qty(), requireReason(req), req.note(), null);
            }
            case COMPENSATION -> refunds.compensate(token, req.negotiatedAmount(), req.note(), null);
        };
        ShopOrderRefund r = out.refund();
        return new CancelResponse(r == null ? null : r.getPublicToken(), out.order().getStatus().name());
    }

    @PostMapping("/admin/stag/shop/orders/{token}/return-inbound")
    @PreAuthorize(AUTH)
    public CancelResponse returnInbound(@PathVariable String token) {
        ShopOrderRefundService.Outcome out = refunds.confirmReturnInbound(token, null);
        return new CancelResponse(out.refund().getPublicToken(), out.order().getStatus().name());
    }

    @PostMapping("/admin/stag/shop/refunds/{token}/paid-out")
    @PreAuthorize(AUTH)
    public ShopOrderRefundView paidOut(@PathVariable String token) {
        return refunds.markPaidOut(token, null);
    }

    @PostMapping("/admin/stag/shop/refunds/{token}/payout-failed")
    @PreAuthorize(AUTH)
    public ShopOrderRefundView payoutFailed(@PathVariable String token,
            @RequestBody(required = false) PayoutFailedRequest req) {
        return refunds.markPayoutFailed(token, req == null ? null : req.note(), null);
    }

    private static CancelReason requireReason(CancelRequest req) {
        if (req.reason() == null) {
            throw AppException.validation("reason 必填");
        }
        return req.reason();
    }
}
