package com.tailtopia.order.dto;

/**
 * 订单状态色语义（Story 5.1，DESIGN.delta 组件①/②）。后端权威返语义，前端映射实际颜色。
 * <b>退款处理中 REFUNDING → {@link #INFO}(蓝) 非 error(红)</b>（UX-DR2：退款不制造焦虑红）。
 */
public enum OrderStatusColor {
    WARN,
    INFO,
    SUCCESS
}
