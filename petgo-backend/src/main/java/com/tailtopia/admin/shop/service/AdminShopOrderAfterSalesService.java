package com.tailtopia.admin.shop.service;

import com.tailtopia.growth.domain.GrowthLedgerEntry;
import com.tailtopia.growth.listener.GrowthRefundListener;
import com.tailtopia.growth.repository.GrowthLedgerRepository;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.dto.ShopOrderRefundView;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.order.refund.ShopRefundStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单抽屉「退款与成长值」一节（V1.4.2 shop-admin Story 7.1 · AD-12 · F7）。只读。
 *
 * <ul>
 *   <li>退款：{@link ShopOrderRefundService#refundViewsOf}（不含全号与户名）；</li>
 *   <li>成长值：当初入账 = {@code SPEND:SHOP:{orderId}} 那一行（须同一用户）；已扣回 = {@code reverses_id} 指向它的合计；</li>
 *   <li>预计待扣回（O-30）：全部<b>未完成</b>现金段（待选择 / 待打款 / 打款失败）按扣回同规则
 *       {@link GrowthRefundListener#proportional} 估算，{@code LATE_PAYMENT} 不扣回；合计不超过「入账 − 已扣回」。只展示，不改扣回逻辑。</li>
 * </ul>
 */
@Service
public class AdminShopOrderAfterSalesService {

    private final ShopOrderRefundService refunds;
    private final GrowthLedgerRepository ledger;

    public AdminShopOrderAfterSalesService(ShopOrderRefundService refunds, GrowthLedgerRepository ledger) {
        this.refunds = refunds;
        this.ledger = ledger;
    }

    /**
     * @param accrued  当初入账成长值；{@code null} = 没有入账行（档位 NONE / 存量单 / 活动未开），页面显示「—」
     * @param reversed 已扣回（≥ 0）
     * @param pending  预计待扣回（≥ 0）
     */
    public record Growth(Integer accrued, long reversed, long pending) {
    }

    public record AfterSales(List<ShopOrderRefundView> refunds, Growth growth) {

        public boolean visible() {
            return !refunds.isEmpty();
        }
    }

    @Transactional(readOnly = true)
    public AfterSales of(ShopOrder order) {
        List<ShopOrderRefundView> views = refunds.refundViewsOf(order);
        if (views.isEmpty()) {
            return new AfterSales(views, new Growth(null, 0, 0));   // 本节只对有退款的订单显示，不再查成长值
        }
        GrowthLedgerEntry original = ledger.findByIdemKey(GrowthIdemKeys.spendShop(order.getId()))
                .filter(e -> e.getUserId() == order.getUserId()).orElse(null);
        if (original == null || original.getDelta() <= 0) {
            return new AfterSales(views, new Growth(null, 0, 0));
        }
        long reversed = Math.max(0, ledger.sumReversed(original.getId()));
        return new AfterSales(views, new Growth(original.getDelta(), reversed,
                pendingReverse(original.getDelta(), reversed, views, order.paidTotal())));
    }

    /** 纯函数：预计待扣回（见类注释）。 */
    static long pendingReverse(int accrued, long reversed, List<ShopOrderRefundView> views, long paidTotal) {
        if (accrued <= 0 || paidTotal <= 0) {
            return 0;
        }
        long sum = 0;
        for (ShopOrderRefundView v : views) {
            if (CancelReason.LATE_PAYMENT.name().equals(v.reason()) || v.cashRefund() <= 0
                    || ShopRefundStatus.COMPLETED.name().equals(v.status())
                    // 补偿单待用户选择时金额未定（页面显示「待用户选择后计算」），不预计（复审修）
                    || ("COMPENSATION".equals(v.scope())
                            && ShopRefundStatus.AWAITING_DESTINATION.name().equals(v.status()))) {
                continue;
            }
            sum += GrowthRefundListener.proportional(accrued, v.cashRefund(), paidTotal);
        }
        return Math.max(0, Math.min(sum, accrued - reversed));
    }
}
