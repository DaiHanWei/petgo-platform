package com.tailtopia.support.domain;

/**
 * 工单关联订单所属的表（Story 3-2 / AD-S7）。落库 {@code varchar(16)} + CHECK，UPPER_SNAKE。
 *
 * <p>🔴 <b>它存在的唯一理由是防串单</b>：{@code consult_orders.id} 与 {@code shop_orders.id}
 * 都是从 1 开始的自增 bigint，<b>数值空间完全重叠</b>。没有这个类型，
 * {@code feedback_tickets.related_order_id = 42} 会被无条件解释成 {@code consult_orders(42)} ——
 * 一条跟本工单毫无关系的问诊单，而退款链路会拿它去建退款请求。
 *
 * <p>⚠️ <b>加第三个值之前先想清楚三处分流都改了没有</b>：
 * 用户侧解析（{@code SupportTicketService.resolveRelatedOrder}）、
 * 后台展示（{@code AdminSupportTicketQueryService}）、
 * 退款守卫（{@code AdminTicketRefundService.ensureRefundRequest}）。
 * 漏掉第三处就是把退款开给新类型的订单。
 */
public enum RelatedOrderType {

    /** {@code consult_orders} —— 问诊单。历史上唯一的取值，存量行全是它。 */
    CONSULT,

    /** {@code shop_orders} —— 电商订单。🔴 <b>不进退款审批链路</b>（本版电商退款走线下）。 */
    SHOP
}
