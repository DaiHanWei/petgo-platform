package com.tailtopia.shop.order.domain;

/**
 * 订单进入 {@code DELIVERED} 走的是哪条出口（Story 4.1，SPEC-2 留痕）。
 *
 * <p>🔴 SPEC-2 要求 {@code SHIPPED} <b>同时</b>具备三条出口，缺任何一条都会死锁。
 * 单独记录来源是为了 Epic 4 联调能逐条验证「这三条各自都真的能让订单脱离 SHIPPED」——
 * 只看最终状态的话，三条边共用一个结果，验一条和验三条看起来一模一样。
 */
public enum DeliverySource {
    /** 所有包裹都被标记送达（S-2 聚合判定，正常路径）。 */
    SHIPMENTS_ALL_DELIVERED,
    /** ① 运营在后台执行「标记已送达」（AB-11B 兜底）。 */
    ADMIN_MARK,
    /** ② 用户在已发货态直接确认收货 —— 用户比谁都先知道货到没到。 */
    USER_CONFIRM,
    /** ③ 发货起 M=7 日无任何标记，{@code @Scheduled} 自动置位。 */
    AUTO_TIMEOUT
}
