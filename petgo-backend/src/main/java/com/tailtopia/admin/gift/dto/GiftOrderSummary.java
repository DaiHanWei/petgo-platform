package com.tailtopia.admin.gift.dto;

/**
 * 送礼记录摘要条（V1.4.0 ③ gift Story 3.2 · AC2.4 / AC3.2）：<b>覆盖整个筛选结果</b>，一条 SQL 聚合（与表格同一组条件）。
 * 全站视图用前五个数，异常单视图用后四个数（同一次聚合一并算出，不再多发一条）。
 *
 * @param orders            送礼笔数 {@code count(*)}
 * @param senders           送礼人数 {@code count(DISTINCT sender_id)}
 * @param deliveredAmount   成功金额：{@code DELIVERED} 的 Σ total
 * @param deliveredCoin     其中 PawCoin：{@code DELIVERED} 且 {@code PAWCOIN} 的 Σ total
 * @param undelivered       已付款未送达笔数（三个异常状态）
 * @param undeliveredAmount 涉及金额：三个异常状态的 Σ total
 * @param refunded          已退回笔数（{@code UNDELIVERED_REFUNDED}）
 * @param refundFailed      退回失败笔数（{@code UNDELIVERED_REFUND_PENDING} + {@code REFUND_MANUAL}）
 */
public record GiftOrderSummary(long orders, long senders, long deliveredAmount, long deliveredCoin,
        long undelivered, long undeliveredAmount, long refunded, long refundFailed) {

    public static final GiftOrderSummary EMPTY = new GiftOrderSummary(0, 0, 0, 0, 0, 0, 0, 0);
}
