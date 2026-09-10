package com.tailtopia.admin.settlement.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.settlement.dto.AdminSettlementDetail;
import com.tailtopia.admin.settlement.dto.AdminSettlementRow;
import com.tailtopia.admin.settlement.dto.SettlementSummary;
import com.tailtopia.consult.domain.VetSettlement;
import com.tailtopia.consult.repository.VetSettlementRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台兽医分成月结对账（Story 9.5，AB-8D）。财务流转 PENDING_FINANCE → PAID（+凭证）→ ARCHIVED，每步审计。
 * 状态机守卫在 {@link VetSettlement}（非法跃迁抛 422）。本 story 是**对账台账 + 状态流转 + 凭证登记**，
 * 不在此发起真实转账（财务线下/Iris 打款后回填凭证）。
 */
@Service
public class AdminSettlementService {

    /** 🔴 月结 period 用 **WIB**（印尼「每月 1 号」本地语义），与 {@code VetSettlementService} 同一口径，勿订正。 */
    private static final java.time.ZoneId WIB = java.time.ZoneId.of("Asia/Jakarta");

    private final VetSettlementRepository settlements;
    private final AdminAuditService audit;
    /** 抽屉里的「订单构成」按 (vetId, period 窗口) 读时重算 —— 月结表只存聚合值，没有明细表。 */
    private final com.tailtopia.consult.repository.ConsultOrderRepository orders;

    public AdminSettlementService(VetSettlementRepository settlements, AdminAuditService audit,
            com.tailtopia.consult.repository.ConsultOrderRepository orders) {
        this.settlements = settlements;
        this.audit = audit;
        this.orders = orders;
    }

    /**
     * 摘要条三格（Story 8.5 · AC3）。入参就是列表那一份 rows，不另查一遍。
     *
     * <p>⚠️ 「本月已打款」判的是 <b>{@code paidAt} 落在本月（WIB）</b>，不是 period 等于本月：
     * 8 月的月结在 9 月初打款，财务问「这个月付出去多少」时它算 9 月。
     */
    public SettlementSummary summary(List<AdminSettlementRow> rows) {
        java.time.YearMonth thisMonth = java.time.YearMonth.now(WIB);
        java.time.Instant monthStart = thisMonth.atDay(1).atStartOfDay(WIB).toInstant();
        java.time.Instant monthEnd = thisMonth.plusMonths(1).atDay(1).atStartOfDay(WIB).toInstant();
        long pendingCount = rows.stream()
                .filter(r -> VetSettlement.PENDING_FINANCE.equals(r.status())).count();
        long pendingPayout = rows.stream()
                .filter(r -> VetSettlement.PENDING_FINANCE.equals(r.status()))
                .mapToLong(AdminSettlementRow::payoutAmount).sum();
        long paidThisMonth = rows.stream()
                .filter(r -> r.paidAt() != null
                        && !r.paidAt().isBefore(monthStart) && r.paidAt().isBefore(monthEnd))
                .mapToLong(AdminSettlementRow::payoutAmount).sum();
        return new SettlementSummary(pendingCount, pendingPayout, paidThisMonth);
    }

    /**
     * 月结抽屉（Story 8.5 · AC3）：台账行 + 订单构成。不存在 → 404。
     *
     * <p>🔴 订单构成的口径与生成月结时**逐字一致**（COMPLETED、按 `sessionEndedAt` 归月、
     * 窗口按 WIB 的 `[start, end)`）—— 差一点点，抽屉里的单数就与台账那一列对不上。
     */
    @Transactional(readOnly = true)
    public AdminSettlementDetail detail(long id) {
        VetSettlement s = settlements.findById(id)
                .orElseThrow(() -> AppException.notFound("月结不存在").code("admin.err.settlement.notFound"));
        java.time.YearMonth period = java.time.YearMonth.parse(s.getPeriod());
        java.time.Instant start = period.atDay(1).atStartOfDay(WIB).toInstant();
        java.time.Instant end = period.plusMonths(1).atDay(1).atStartOfDay(WIB).toInstant();
        List<AdminSettlementDetail.OrderRow> rows =
                orders.findCompletedForVetInWindow(s.getVetId(), start, end).stream()
                        .map(o -> new AdminSettlementDetail.OrderRow(o.getOrderToken(),
                                com.tailtopia.order.dto.OrderDisplayNo.of(
                                        com.tailtopia.order.dto.OrderDisplayNo.VET_CONSULT,
                                        o.getId(), o.getCreatedAt()),
                                o.getAmount(), o.getVetPayout(), o.getSessionEndedAt()))
                        .toList();
        return new AdminSettlementDetail(s.getId(), s.getVetId(), s.getPeriod(), s.getOrderCount(),
                s.getGrossAmount(), s.getPayoutAmount(), s.getStatus(), s.getPaymentProof(),
                s.getPaidAt(), s.getArchivedAt(), s.getGeneratedAt(), rows);
    }

    @Transactional(readOnly = true)
    public List<AdminSettlementRow> list() {
        return settlements.findAll(Sort.by(Sort.Direction.DESC, "generatedAt")).stream()
                .map(AdminSettlementService::toRow).toList();
    }

    /** 财务确认打款：PENDING_FINANCE→PAID + 凭证 + 审计。 */
    @Transactional
    public void markPaid(long id, String proof, long adminId) {
        VetSettlement s = settlements.findById(id)
                .orElseThrow(() -> AppException.notFound("月结不存在").code("admin.err.settlement.notFound"));
        s.markPaid(proof, adminId);
        settlements.save(s);
        audit.record(adminId, "SETTLEMENT_PAID", "vet_settlement", String.valueOf(id),
                "vet=" + s.getVetId() + " period=" + s.getPeriod() + " payout=" + s.getPayoutAmount());
    }

    /** 归档：PAID→ARCHIVED + 审计。 */
    @Transactional
    public void archive(long id, long adminId) {
        VetSettlement s = settlements.findById(id)
                .orElseThrow(() -> AppException.notFound("月结不存在").code("admin.err.settlement.notFound"));
        s.archive(adminId);
        settlements.save(s);
        audit.record(adminId, "SETTLEMENT_ARCHIVED", "vet_settlement", String.valueOf(id),
                "vet=" + s.getVetId() + " period=" + s.getPeriod());
    }

    private static AdminSettlementRow toRow(VetSettlement s) {
        return new AdminSettlementRow(s.getId(), s.getVetId(), s.getPeriod(), s.getOrderCount(),
                s.getGrossAmount(), s.getPayoutAmount(), s.getStatus(), s.getPaymentProof(),
                s.getPaidAt(), s.getGeneratedAt());
    }
}
