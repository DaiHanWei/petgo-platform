package com.tailtopia.admin.settlement.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.settlement.dto.AdminSettlementRow;
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

    private final VetSettlementRepository settlements;
    private final AdminAuditService audit;

    public AdminSettlementService(VetSettlementRepository settlements, AdminAuditService audit) {
        this.settlements = settlements;
        this.audit = audit;
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
                .orElseThrow(() -> AppException.notFound("月结不存在"));
        s.markPaid(proof, adminId);
        settlements.save(s);
        audit.record(adminId, "SETTLEMENT_PAID", "vet_settlement", String.valueOf(id),
                "vet=" + s.getVetId() + " period=" + s.getPeriod() + " payout=" + s.getPayoutAmount());
    }

    /** 归档：PAID→ARCHIVED + 审计。 */
    @Transactional
    public void archive(long id, long adminId) {
        VetSettlement s = settlements.findById(id)
                .orElseThrow(() -> AppException.notFound("月结不存在"));
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
