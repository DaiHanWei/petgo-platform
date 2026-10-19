package com.tailtopia.consult.event;

/**
 * 问诊以纯 PawCoin 支付成功、订单已建（V1.4.0 ① Story 1.3，过去式）。
 *
 * <p>为什么不靠 {@code PawCoinSpentEvent}：问诊纯币 {@code debit} 的 refId 是 {@code consult_requests.id}，而该请求行在
 * 同一事务里被删、订单也不存它 —— 退款侧只拿得到订单，无法反查原消费流水。故 ① 对 {@code VET_CONSULT} 的纯币扣款事件
 * 不计，改由本事件以问诊订单 id 记消费返（架构增量以 Story 1.3 为准，2026-10-18 拍板采纳）。
 *
 * @param userId         付款用户
 * @param consultOrderId 问诊订单 id
 * @param amount         实付 PawCoin（1 币 = Rp1）
 */
public record ConsultPaidByPawCoinEvent(long userId, long consultOrderId, long amount) {
}
