package com.tailtopia.pay.refund.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款职责分离**违规审计**独立事务留痕（Story 4.3，A-1）。
 *
 * <p>守卫拦截后需既抛异常又留审计——{@link AdminAuditService#record} 默认 REQUIRED 会随异常回滚导致审计丢失
 * （历史 AFTER_COMMIT 事务吞写坑 [[notify-after-commit-tx-swallow-bug]]）。故违规审计走 {@code REQUIRES_NEW}
 * 独立提交，caller 再抛异常回滚自身事务不影响本审计。
 */
@Service
public class RefundAuditRecorder {

    private final AdminAuditService audit;

    public RefundAuditRecorder(AdminAuditService audit) {
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordViolation(long actorAdminId, String refundToken, String summary) {
        audit.record(actorAdminId, AuditActions.REFUND_DUTY_VIOLATION_BLOCKED, "refund_request",
                refundToken, summary);
    }
}
