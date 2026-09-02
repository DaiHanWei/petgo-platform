package com.tailtopia.shop.returns.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.dto.ReturnEligibilityView;
import com.tailtopia.shop.returns.dto.ReturnProgressView;
import com.tailtopia.shop.returns.dto.ReturnableLineView;
import com.tailtopia.shop.returns.repository.ReturnLineRepository;
import com.tailtopia.shop.returns.repository.ReturnRequestRepository;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退货申请页 / 进度页的读模型（Story 5.7 / 5.8 / 5.9）。
 *
 * <p>🔴 <b>「能不能退、为什么不能退」全部由服务端判定并下发文案依据</b>：
 * 前端不重算规则。规则散在两侧的后果不是不一致，而是<b>只在某些行上不一致</b> ——
 * 那种问题在测试里几乎撞不上，在用户手里天天撞上。
 */
@Service
public class ReturnQueryService {

    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final ReturnRequestRepository returns;
    private final ReturnLineRepository returnLines;
    private final ShippingSettingsRepository settings;
    private final RefundExecutionService refunds;

    public ReturnQueryService(ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            ReturnRequestRepository returns, ReturnLineRepository returnLines,
            ShippingSettingsRepository settings, RefundExecutionService refunds) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.returns = returns;
        this.returnLines = returnLines;
        this.settings = settings;
        this.refunds = refunds;
    }

    /** 退货申请页数据。🔴 不可退的行<b>保留可见但置灰</b>，不过滤掉（5.7 AC）。 */
    @Transactional(readOnly = true)
    public ReturnEligibilityView eligibility(long userId, String orderToken) {
        ShopOrder order = orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("订单不存在"));

        // UX-DR3 / C-12：已有进行中申请 → 入口置灰
        ReturnRequest active = returns.findActiveByOrder(order.getId()).orElse(null);

        String reason = null;
        boolean eligible = true;
        if (active != null) {
            eligible = false;
            reason = "已有退货申请处理中";
        } else if (!isReturnableStatus(order)) {
            eligible = false;
            reason = "当前订单状态不可申请退货";
        } else if (isPostDelivery(order) && !order.isWithinReturnWindow(Instant.now())) {
            eligible = false;
            reason = "已超过签收后 7 天的退货期限";
        }

        List<ReturnableLineView> lines = new ArrayList<>();
        for (ShopOrderLine l : orderLines.findByOrderIdOrderByIdAsc(order.getId())) {
            int returnable = l.getQty() - l.getRefundedQty();
            ReturnPolicy policy = l.getReturnPolicy();
            boolean selectable = returnable > 0;
            // 🔴 下发**原因码**而不是文案（D-9）：此前这里是中文串，而 App 没有中文包、
            //    这句也不经 i18n ⇒ 印尼用户在退货申请页**必现**中文。
            //    ⚠️ 搬进后端 messages.properties 解决不了：api 链的默认 locale 是 zh_CN
            //       （见 AdminLocaleConfig 的注释「api 链返 JSON，文案固定，不经此」）。
            //       展示文案属于端上。
            String blocked = null;
            if (returnable <= 0) {
                blocked = "ALL_RETURNED";
            } else if (policy == null || policy == ReturnPolicy.NON_RETURNABLE) {
                // 🔴 未知枚举降级到最保守档：宁可少承诺
                selectable = false;
                blocked = "NON_RETURNABLE";
            } else if (policy == ReturnPolicy.NO_RETURN_AFTER_OPEN) {
                // 🔴 「开封不退」三处明示的第 3 处：保留可见 + 置灰 + 直接标注原因，
                //    比提交后再驳回体验好得多。质量问题另有口径，前端切换原因时可再放开。
                selectable = false;
                blocked = "NO_RETURN_AFTER_OPEN";
            }
            lines.add(new ReturnableLineView(l.getId(), l.getProductName(), l.getSpecName(),
                    l.getUnitPrice(), l.getQty(), l.getRefundedQty(), Math.max(0, returnable),
                    policy == null ? ReturnPolicy.NON_RETURNABLE.name() : policy.name(),
                    selectable, blocked));
        }

        ShippingSettings s = settings.findAll().stream().findFirst().orElse(null);
        ReturnEligibilityView.ReturnAddressView addr =
                s != null && s.hasReturnAddress()
                        ? new ReturnEligibilityView.ReturnAddressView(s.getReturnReceiverName(),
                                s.getReturnReceiverPhone(), s.getReturnAddressText())
                        : null;

        return new ReturnEligibilityView(orderToken, eligible, reason,
                active == null ? null : active.getPublicToken(), order.returnWindowEndsAt(), lines,
                addr);
    }

    /** 退货进度 / 退款方式页数据。 */
    @Transactional(readOnly = true)
    public ReturnProgressView progress(long userId, String returnToken) {
        ReturnRequest r = returns.findByPublicTokenAndUserId(returnToken, userId)
                .orElseThrow(() -> AppException.notFound("退货申请不存在"));
        ShopOrder order = orders.findById(r.getShopOrderId()).orElseThrow();
        var rls = returnLines.findByReturnRequestIdOrderByIdAsc(r.getId());
        List<String> labels = new ArrayList<>();
        for (var rl : rls) {
            labels.add(orderLines.findById(rl.getOrderLineId())
                    .map(ol -> ol.getProductName() + " · " + ol.getSpecName()).orElse(""));
        }
        // 试算失败（如状态已终结）不该让整页 500 —— 金额区块留空即可
        RefundExecutionService.Quote quote;
        try {
            quote = refunds.quote(returnToken);
        } catch (RuntimeException e) {
            quote = null;
        }
        return ReturnProgressView.of(r, order.getPublicToken(), rls, labels, quote);
    }

    @Transactional(readOnly = true)
    public List<ReturnProgressView> myReturns(long userId) {
        List<ReturnProgressView> out = new ArrayList<>();
        for (ReturnRequest r : returns.findByUserIdOrderByCreatedAtDescIdDesc(userId)) {
            out.add(progress(userId, r.getPublicToken()));
        }
        return out;
    }

    private static boolean isReturnableStatus(ShopOrder o) {
        ShopOrderStatus s = o.getStatus();
        return s == ShopOrderStatus.PENDING_SHIPMENT || s == ShopOrderStatus.SHIPPED
                || s == ShopOrderStatus.DELIVERED || s == ShopOrderStatus.COMPLETED;
    }

    private static boolean isPostDelivery(ShopOrder o) {
        return o.getStatus() == ShopOrderStatus.DELIVERED
                || o.getStatus() == ShopOrderStatus.COMPLETED;
    }
}
