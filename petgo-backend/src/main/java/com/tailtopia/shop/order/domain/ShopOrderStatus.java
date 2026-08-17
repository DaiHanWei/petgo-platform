package com.tailtopia.shop.order.domain;

import java.util.Set;

/**
 * 电商订单状态（Story 3.2，八态）。
 *
 * <p>🔴 <b>合法迁移集中在本枚举，不散落在各 service</b>（照既有 {@code PaymentIntent} 范式）——
 * 散落的状态判断迟早互相矛盾，而订单状态是履约、退款、对账三段的唯一权威来源。
 *
 * <p>⚠️ <b>各 Epic 只加自己的边，不提前实现未来 Epic 的迁移</b>：提前开边等于允许出现
 * 一条没有任何代码能推进的悬空状态。
 * Epic 3 开了「待支付 → 待发货 / 已取消」；<b>Epic 4（Story 4.1）追加履约段</b>；
 * 退款中/已退款仍属 Epic 5，本枚举尚未开放。
 *
 * <p>🔴 <b>SPEC-2 死锁防线：{@code SHIPPED} 有三条出口</b>（缺任何一条都会死锁）——
 * ① 后台「标记已送达」② 用户在已发货态直接确认收货 ③ 发货起 M=7 日自动置送达。
 * 三条出口全部经由 {@code DELIVERED}：<b>不开 {@code SHIPPED → COMPLETED} 直达边</b>，
 * 因为「签收时刻」（SPEC-5，= 进入 {@code DELIVERED} 的时刻）是 Epic 5 退货窗口的唯一起算点，
 * 直达会造出一笔没有签收时刻、退货窗口无从算起的已完成订单。
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
     * 当前已开放的边（Epic 3 支付段 + Epic 4 履约段）。
     *
     * <p>Epic 3：支付成功 → 待发货；超时/取消 → 已取消。
     * <p>Epic 4：待发货 → 已发货 → 已送达 → 已完成，外加待发货可被运营取消（异常订单出口，4.4）。
     */
    public boolean canTransitionTo(ShopOrderStatus next) {
        return switch (this) {
            case PENDING_PAYMENT -> next == PENDING_SHIPMENT || next == CANCELLED;
            // 🔴 已付款订单的取消出口（超卖/缺货/地址异常，Story 4.4）。取消必然伴随全额退款，
            //    但退款【执行】属 Epic 5 的资金链路，本枚举只负责这条状态边存在。
            case PENDING_SHIPMENT -> next == SHIPPED || next == CANCELLED;
            // 🔴 三条出口共用这一条边（后台标记 / 用户确认 / M 日自动），差别只在 deliverySource。
            case SHIPPED -> next == DELIVERED;
            case DELIVERED -> next == COMPLETED;
            // Epic 5 会在各自 story 里补退款段的边。此处返回 false 不是「禁止」，
            // 而是「尚未实现」——提前开边会造出没有代码能推进的悬空态。
            default -> false;
        };
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
