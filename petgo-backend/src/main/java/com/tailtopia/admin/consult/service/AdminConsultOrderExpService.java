package com.tailtopia.admin.consult.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.consult.dto.AdminConsultOrderRow;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.growth.domain.GrowthLedgerEntry;
import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.repository.GrowthLedgerRepository;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.ReverseCommand;
import com.tailtopia.growth.service.ReverseOutcome;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 问诊挂起 QRIS 退款的成长值人工扣回（V1.4.2 exp-reverse，修 1.4.0 架构 P-10 缺口）。
 *
 * <p>{@link #state} 是扣回判定的<b>单点</b>：抽屉、弹层、列表筛选、执行都只认它（AD-2）。
 * 扣回直调 {@link GrowthLedgerService}、不发退款事件（E-6）：事件监听异步吞异常，拿不到实扣额，也进不了审计事务；
 * 来源键与 PawCoin 分支同为 {@code consult-refund-{orderId}}，同一订单不会重复扣。
 */
@Service
public class AdminConsultOrderExpService {

    /**
     * 成长值节的展示与判定。
     *
     * @param accrued    当初入账；{@code null} = 无入账流水（E-4）
     * @param reversed   已扣回
     * @param pending    本次可扣回额，不可扣回时为 0
     * @param reversible 是否可人工扣回
     */
    public record ConsultExpState(Integer accrued, long reversed, long pending, boolean reversible) {
    }

    private final ConsultOrderRepository orders;
    private final GrowthLedgerService growth;
    private final GrowthLedgerRepository ledger;
    private final AdminAuditService audit;

    public AdminConsultOrderExpService(ConsultOrderRepository orders, GrowthLedgerService growth,
            GrowthLedgerRepository ledger, AdminAuditService audit) {
        this.orders = orders;
        this.growth = growth;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ConsultExpState state(ConsultOrder o) {
        GrowthLedgerEntry original = original(o);
        if (original == null) {
            return new ConsultExpState(null, 0, 0, false);
        }
        long reversed = Math.max(0, ledger.sumReversed(original.getId()));
        boolean candidate = o.getStatus() == ConsultOrderStatus.REFUNDING
                && o.getSuspensionRefundAt() != null && reversed == 0;
        long lifetime = candidate ? growth.lifetimeTotal(o.getUserId()) : 0;
        boolean reversible = candidate && lifetime > 0;
        return new ConsultExpState(original.getDelta(), reversed,
                reversible ? Math.min(original.getDelta(), lifetime) : 0, reversible);
    }

    @Transactional(readOnly = true)
    public ConsultExpState state(String orderToken) {
        return state(load(orderToken));
    }

    /** 弹层用：取可扣回状态，不可扣回抛 422。 */
    @Transactional(readOnly = true)
    public ConsultExpState requireReversible(String orderToken) {
        return requireReversible(load(orderToken));
    }

    private ConsultExpState requireReversible(ConsultOrder o) {
        ConsultExpState s = state(o);
        if (!s.reversible()) {
            throw s.reversed() > 0 ? alreadyReversed() : notReversible();
        }
        return s;
    }

    /** 列表筛选「待扣回成长值」：只含可扣回的订单（E-7）。 */
    @Transactional(readOnly = true)
    public List<AdminConsultOrderRow> pendingRows() {
        return orders.findByStatusAndSuspensionRefundAtIsNotNullOrderByCreatedAtDesc(ConsultOrderStatus.REFUNDING)
                .stream().filter(o -> state(o).reversible()).map(AdminConsultOrderService::toRow).toList();
    }

    /** 执行扣回，返回实扣额。业务错误一律 422（E-8）：后台 htmx 不放行 409，用户会看不到提示。 */
    @Transactional
    public int reverse(String orderToken, long adminId) {
        ConsultOrder o = load(orderToken);
        ConsultExpState s = requireReversible(o);
        ReverseOutcome r = growth.reverseDetailed(new ReverseCommand(
                GrowthIdemKeys.consultSpend(o.getId(), o.getPaymentIntentId()), ReverseReason.REFUND,
                GrowthIdemKeys.refundSource("consult-refund-" + o.getId()), null));
        if (r.status() == ReverseOutcome.Status.ALREADY_REVERSED) {
            throw alreadyReversed();
        }
        if (!r.done()) {
            throw notReversible();
        }
        audit.record(adminId, AuditActions.CONSULT_ORDER_EXP_REVERSED, "consult_order", orderToken,
                "accrued=" + s.accrued() + " reversedBefore=" + s.reversed() + " reversed=" + r.amount()
                        + " levelBefore=" + r.levelBefore() + " levelAfter=" + r.levelAfter());
        return r.amount();
    }

    private GrowthLedgerEntry original(ConsultOrder o) {
        return ledger.findByIdemKey(GrowthIdemKeys.consultSpend(o.getId(), o.getPaymentIntentId()))
                .filter(e -> e.getUserId() == o.getUserId() && e.getDelta() > 0)
                .orElse(null);
    }

    private ConsultOrder load(String orderToken) {
        return orders.findByOrderToken(orderToken)
                .orElseThrow(() -> AppException.notFound("订单不存在").code("admin.err.order.notFound"));
    }

    private static AppException alreadyReversed() {
        return AppException.validation("这单的成长值已经扣回过了").code("admin.err.consultExp.alreadyReversed");
    }

    private static AppException notReversible() {
        return AppException.validation("这单已不能扣回").code("admin.err.consultExp.notReversible");
    }
}
