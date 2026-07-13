package com.tailtopia.consult.domain;

/**
 * 兽医咨询请求状态（Story 3.1，付费前临时态 CAS 状态机）。落库 varchar UPPER_SNAKE。
 *
 * <p>仅两活态：{@code QUEUEING}（入队待接单）→ {@code ACCEPTED_AWAIT_PAY}（兽医接单、待用户限时支付）。
 * <b>无 CANCELLED</b>——取消/超时=物理删行（A-5「订单中心无已取消态」根因）。支付成功即转 {@code consult_orders}
 * 并删本 request 行。所有 accept/cancel 竞态经 {@code state} 单列 compare-and-set（H-4，非时间戳比较）。
 */
public enum ConsultRequestState {
    QUEUEING,
    ACCEPTED_AWAIT_PAY
}
