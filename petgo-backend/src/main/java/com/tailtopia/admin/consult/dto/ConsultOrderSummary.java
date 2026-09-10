package com.tailtopia.admin.consult.dto;

/**
 * B10 兽医订单摘要条三格（V1.3.0 Story 8.4 · AC1）：本期成交额 · 单数 · 待核查数。
 *
 * <p>⚠️ 「本期」= <b>当前筛选集</b>。本页现状没有任何筛选参数，所以它暂时等于全量 ——
 * 这一点必须在页面上写明，否则运营会把它读成「本月」。
 *
 * <p>⚠️ 三个数取自**同一份列表**，不另查一遍：分开查的话跨秒时「待核查数」可能比「单数」还大。
 *
 * @param gross   成交额之和（IDR，最小单位）
 * @param count   单数
 * @param toVerify 待核查（{@code TO_VERIFY}）的单数
 */
public record ConsultOrderSummary(long gross, long count, long toVerify) {
}
