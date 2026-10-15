package com.tailtopia.ops.popup.dto;

import com.tailtopia.ops.target.OpsTargetView;

/**
 * 候选弹窗下发项（AC3.5）。全局 NON_NULL：null 字段整个省略。
 *
 * <p>🔴 {@code token} = {@code public_token}（D-24），<b>不含</b>自增 id、name、priority、audience、
 * new_user_days、status、时间窗 —— 那些是运营配置，App 用不到，下发即泄露。
 */
public record OpsPopupCandidateView(
        String token,
        String frequency,
        String imageUrlId,
        String imageUrlEn,
        String titleId,
        String titleEn,
        String bodyId,
        String bodyEn,
        String buttonTextId,
        String buttonTextEn,
        OpsTargetView target) {
}
