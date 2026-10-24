package com.tailtopia.merchant.po.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 进货单状态（契约 5.2）。合法迁移集中在本枚举，终态 {@code COMPLETED} / {@code CANCELLED} 不再变。
 *
 * <p>v1 没有改单 / 退款（FR-15 不在范围），也没有手工取消：唯一的取消是 60 分钟未付自动取消（FR-13、D7）。
 */
public enum PurchaseOrderStatus {
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    COMPLETED,
    CANCELLED;

    /** 「已付款」口径（FR-5A、接口 5 汇总）。 */
    public static final Set<PurchaseOrderStatus> PAID_STATES = EnumSet.of(PAID, SHIPPED, DELIVERED, COMPLETED);

    public boolean canTransitionTo(PurchaseOrderStatus next) {
        return switch (this) {
            case PENDING_PAYMENT -> next == PAID || next == CANCELLED;
            case PAID -> next == SHIPPED;
            case SHIPPED -> next == DELIVERED;
            case DELIVERED -> next == COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
