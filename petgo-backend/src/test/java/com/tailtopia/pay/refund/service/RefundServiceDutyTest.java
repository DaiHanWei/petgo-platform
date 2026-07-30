package com.tailtopia.pay.refund.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.pay.refund.domain.ApprovalStatus;
import com.tailtopia.pay.refund.domain.NeedDecision;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * L0：退款职责分离守卫（Story 4.3，最高危 A-1）。守卫为 admin_id 相等判定（不看角色 → SUPER_ADMIN 不豁免）；
 * 违规即拒 403 + 独立事务留审计，且不发生状态变更。
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceDutyTest {

    @Mock private RefundRequestRepository refunds;
    @Mock private ConsultOrderRepository orders;
    @Mock private CardTokenGenerator tokenGenerator;
    @Mock private AdminAuditService audit;
    @Mock private RefundAuditRecorder auditRecorder;

    @InjectMocks private RefundService service;

    /** submitter=5, approver=可选 的退款单。 */
    private RefundRequest seed(long submitterId, Long approverId) {
        RefundRequest r = RefundRequest.create(1L, null, 100L, "tok", 50000);
        r.markNeedDecision(NeedDecision.APPROVED, submitterId);
        if (approverId != null) {
            r.approve(approverId);
        }
        when(refunds.findByRefundToken("tok")).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void approve_sameAdminAsSubmitter_blocked() {
        RefundRequest r = seed(5L, null);
        assertThatThrownBy(() -> service.approve("tok", 5L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(auditRecorder).recordViolation(eq(5L), eq("tok"), anyString());
        assertThat(r.getApprovalStatus()).isNull(); // 未审批
    }

    @Test
    void approve_distinctAdmin_ok() {
        RefundRequest r = seed(5L, null);
        service.approve("tok", 6L);
        assertThat(r.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(r.getApproverAdminId()).isEqualTo(6L);
        verify(auditRecorder, never()).recordViolation(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
    }

    @Test
    void payout_sameAsSubmitter_blocked() {
        seed(5L, 6L);
        assertThatThrownBy(() -> service.recordPayout("tok", 5L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(auditRecorder).recordViolation(eq(5L), eq("tok"), anyString());
    }

    @Test
    void payout_sameAsApprover_blocked() {
        seed(5L, 6L);
        assertThatThrownBy(() -> service.recordPayout("tok", 6L))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(auditRecorder).recordViolation(eq(6L), eq("tok"), anyString());
    }

    @Test
    void payout_distinctAdmin_ok() {
        RefundRequest r = seed(5L, 6L);
        service.recordPayout("tok", 7L);
        assertThat(r.getApprovalStatus()).isEqualTo(ApprovalStatus.DONE);
        assertThat(r.getPayerAdminId()).isEqualTo(7L);
    }
}
