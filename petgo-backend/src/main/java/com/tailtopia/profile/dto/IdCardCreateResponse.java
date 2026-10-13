package com.tailtopia.profile.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 建卡接口响应（V1.3.4 ktp AD-4）：{@link IdCardResponse} 全部字段平铺 + {@code numberReused}。
 * 列表 / 详情仍返回 {@link IdCardResponse}，不带该键。
 *
 * <p>{@code numberReused}：新卡是否沿用了已解锁学生卡的编号（埋点 {@code id_card_created.number_reused} 用）。
 * Story 1.1 恒 {@code false}，Epic 2 Story 2.3 接真值。
 */
public record IdCardCreateResponse(@JsonUnwrapped IdCardResponse card, boolean numberReused) {

    public static IdCardCreateResponse of(IdCardResponse card, boolean numberReused) {
        return new IdCardCreateResponse(card, numberReused);
    }
}
