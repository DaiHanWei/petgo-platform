package com.tailtopia.admin.moderation.dto;

import java.util.Map;

/**
 * 统一复核工作台「祝福语举报」页签右栏快照（V1.5.0 gift-pack Story 1-5 · AC3.4）：祝福语全文、礼物名 × 数量、送礼人、
 * 收礼宠物名、举报理由分布。🔴 祝福语可以展示（运营要看的证据），严禁写日志。
 *
 * @param message       祝福语全文（已隐藏也展示原文，供复核）
 * @param hidden        已被隐藏
 * @param reasonCounts  举报理由 → 条数（按理由名排序）
 */
public record GiftMessageReviewView(long orderId, String message, boolean hidden, String giftNameEn, String giftNameId,
        int quantity, Long senderUserId, String senderNickname, boolean senderDeleted, String petName,
        Map<String, Long> reasonCounts) {
}
