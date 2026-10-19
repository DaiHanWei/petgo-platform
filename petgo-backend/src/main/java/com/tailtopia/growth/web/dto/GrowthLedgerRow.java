package com.tailtopia.growth.web.dto;

import java.time.Instant;

/**
 * EXP 明细一行（V1.4.0 ① Story 1.4 · AD-16）。只下发代码与数字；{@code note} 是运营填写的原文，原样下发。
 * <b>不下发</b> 流水 id、ref_id、pet_ids、操作人、批次号、幂等键。
 *
 * @param ruleCode   规则代码
 * @param delta      增减值（扣回为负）
 * @param reason     扣回 / 调整原因代码；正向入账为 null
 * @param note       运营原因或活动名（仅 {@code OPS_CAMPAIGN} / {@code ADMIN_ADJUST}）
 * @param detailCode 补充代码：{@code SPEND_*} → 支付用途；{@code POST_PUBLISHED} → 帖子类型；其余 null
 * @param createdAt  UTC
 */
public record GrowthLedgerRow(String ruleCode, int delta, String reason, String note, String detailCode,
        Instant createdAt) {
}
