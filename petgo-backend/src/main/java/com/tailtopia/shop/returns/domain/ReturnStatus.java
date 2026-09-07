package com.tailtopia.shop.returns.domain;

import java.util.Set;

/**
 * 退款单状态（Story 5.1，FR-104A / AD-5 / S-8）。
 *
 * <p>🔴 <b>退款单的状态机独立于订单状态机</b>（AD-5）：部分退款完成时订单主状态不变。
 * 两者混为一谈会让「退了一行的订单」被整单标记为已退款，连带毁掉 AB-13A 售后成本
 * 与 AB-13D 对账。
 *
 * <p>🔴 <b>SPEC-6 的四条缺边已在此闭合</b>（S-8）：
 * <ol>
 *   <li><b>拒收</b> —— {@link ReturnType#REFUSED_ON_DELIVERY}，批准后跳过寄回与质检直接退款；</li>
 *   <li><b>退款驳回回边</b> —— {@link #REJECTED} <b>不是终态</b>：订单回到驳回前状态，
 *       用户可再次申请。若它是终态，FR-102「无悬空态」按图论就为假；</li>
 *   <li><b>退款执行失败</b> —— {@link #REFUND_FAILED} 中间态，可重试，超 3 次转人工；</li>
 *   <li><b>用户主动撤销</b> —— {@link #PENDING_REVIEW} / {@link #AWAIT_SHIPBACK} 可
 *       {@link #WITHDRAWN}，订单回到原状态。</li>
 * </ol>
 */
public enum ReturnStatus {

    /** 待 CS 审核。 */
    PENDING_REVIEW,
    /**
     * 🔚 已驳回。<b>不是纯终态</b>（S-10）：须能展示驳回原因 + 质检照片 + 商品处置方式，
     * 用户可申诉（复用 FR-52 工单），也可就同一订单重新申请。
     */
    REJECTED,
    /** 待用户寄回（S-7 用户自寄；超 7 日未寄回 → {@link #CLOSED}）。 */
    AWAIT_SHIPBACK,
    /** 质检中。 */
    INSPECTING,
    /** 退款执行中。 */
    REFUNDING,
    /** 🔚 已退款。 */
    REFUNDED,
    /** 退款执行失败（S-8 ③）。可重试；{@code refundAttempts > 3} 转人工。 */
    REFUND_FAILED,
    /** 🔚 超时未寄回而关闭。 */
    CLOSED,
    /** 🔚 用户主动撤销（S-8 ④）。 */
    WITHDRAWN;

    /**
     * 🔴 <b>「进行中」的定义必须与库级部分唯一索引
     * {@code uq_return_requests_active_per_order} 的 WHERE 子句逐字一致。</b>
     *
     * <p>两处不一致时，应用层会以为还能再建一张而数据库拒绝（或反之），
     * 表现为一个只在特定状态下复现的插入失败。
     */
    public static final Set<ReturnStatus> ACTIVE =
            Set.of(PENDING_REVIEW, AWAIT_SHIPBACK, INSPECTING, REFUNDING, REFUND_FAILED);

    /** 终态：不再改动这笔订单的钱或货。REJECTED 在此列，但它允许用户重新申请。 */
    public static final Set<ReturnStatus> TERMINAL =
            Set.of(REJECTED, REFUNDED, CLOSED, WITHDRAWN);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 合法迁移。🔴 集中在本枚举，不散落到各 service（照 {@code ShopOrderStatus} 范式）。 */
    public boolean canTransitionTo(ReturnStatus next) {
        return switch (this) {
            // 审核：批准 → 待寄回；拒收/发货前取消跳过寄回与质检 → 直接退款执行；驳回；用户撤销
            case PENDING_REVIEW -> next == AWAIT_SHIPBACK || next == REFUNDING
                    || next == REJECTED || next == WITHDRAWN;
            // 寄回：已寄回 → 质检；超时 → 关闭；用户仍可撤销
            case AWAIT_SHIPBACK -> next == INSPECTING || next == CLOSED || next == WITHDRAWN;
            // 质检：通过 → 退款执行；不通过 → 驳回
            case INSPECTING -> next == REFUNDING || next == REJECTED;
            // 退款执行：成功 / 失败
            case REFUNDING -> next == REFUNDED || next == REFUND_FAILED;
            // 🔴 失败可重试（回到 REFUNDING）；超 3 次由人工在后台转 REJECTED 收口
            case REFUND_FAILED -> next == REFUNDING || next == REJECTED;
            default -> false;
        };
    }
}
