package com.tailtopia.shop.order.domain;

import java.util.Set;

/**
 * 电商订单状态（Story 3.2，八态）。
 *
 * <p>🔴 <b>合法迁移集中在本枚举，不散落在各 service</b>（照既有 {@code PaymentIntent} 范式）——
 * 散落的状态判断迟早互相矛盾，而订单状态是履约、退款、对账三段的唯一权威来源。
 *
 * <p>⚠️ <b>Epic 3 只开「待支付 → 待发货 / 已取消」这一段的边。</b>
 * 已发货/已送达/已完成属 Epic 4，退款中/已退款属 Epic 5 ——
 * <b>各 Epic 只加自己的边，不提前实现未来 Epic 的迁移</b>：提前开边等于允许出现
 * 一条没有任何代码能推进的悬空状态。
 *
 * <p>⚠️ SPEC-6 指出状态机还缺四条边（拒收 / 退款驳回回边 / 退款执行失败分支 /
 * 用户主动撤销退货），须在 Epic 5 前闭合。
 */
public enum ShopOrderStatus {

    /** 已下单未支付。库存已锁定（Story 1.2 的 lock），60 分钟超时释放（AD-8）。 */
    PENDING_PAYMENT,
    /** 已支付待发货。 */
    PENDING_SHIPMENT,
    /** 已发货（Epic 4）。 */
    SHIPPED,
    /** 已送达（Epic 4）。 */
    DELIVERED,
    /** 🔚 终态：已完成。 */
    COMPLETED,
    /** 🔚 终态：已取消。 */
    CANCELLED,
    /** 退款中（Epic 5）。 */
    REFUNDING,
    /** 🔚 终态：已退款。 */
    REFUNDED;

    /** 三个终态，无悬空。 */
    public static final Set<ShopOrderStatus> TERMINAL =
            Set.of(COMPLETED, CANCELLED, REFUNDED);

    /**
     * Epic 3 开放的边。
     *
     * <p>🔴 只有两条：支付成功 → 待发货；超时/取消 → 已取消。
     */
    public boolean canTransitionTo(ShopOrderStatus next) {
        return switch (this) {
            case PENDING_PAYMENT -> next == PENDING_SHIPMENT || next == CANCELLED;
            // Epic 4/5 会在各自 story 里补自己的边。此处返回 false 不是「禁止」，
            // 而是「本 Epic 尚未实现」——提前开边会造出没有代码能推进的悬空态。
            default -> false;
        };
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
