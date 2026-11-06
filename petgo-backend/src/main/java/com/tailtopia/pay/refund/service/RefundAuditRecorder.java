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

    /**
     * 电商取消退款单的职责分离违规审计（V1.4.2 shop-admin Story 5.2 · O-12）：同样 {@code REQUIRES_NEW} 独立提交，
     * target = {@code SHOP_ORDER_REFUND}，动作 {@code SHOP_REFUND_DUTY_VIOLATION_BLOCKED}。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordShopRefundViolation(long actorAdminId, String refundToken, String summary) {
        audit.record(actorAdminId, AuditActions.SHOP_REFUND_DUTY_VIOLATION_BLOCKED, "SHOP_ORDER_REFUND",
                refundToken, summary);
    }
}
