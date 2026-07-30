package com.tailtopia.consult.dto;

/**
 * 兽医到手聚合投影（Story 3.7）：某兽医某区间 COMPLETED 订单的聚合（月结生成 + 当月待结算实时聚合共用）。
 * JPQL {@code SELECT new ...} 构造；金额 bigint IDR，count/sum 为 Long（空区间由调用方兜底 0）。
 */
public record VetPayoutAggregate(long vetId, long orderCount, long grossAmount, long payoutAmount) {
}
