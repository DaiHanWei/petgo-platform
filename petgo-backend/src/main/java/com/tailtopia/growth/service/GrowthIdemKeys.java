package com.tailtopia.growth.service;

import java.time.LocalDate;

/**
 * 成长值幂等键（V1.4.0 ① 架构 §5.2 / AD-6）。🔴 <b>唯一</b>生成幂等键的地方，禁止在别处手拼字符串——
 * 防刷全靠幂等键（{@code growth_ledger.idem_key} 唯一约束），格式一旦分叉就会让同一行为入账两次。
 *
 * <p>格式 {@code {类型}:{字段}:{字段}}。点赞 / 收藏的键<b>不含时间</b> = 终身一次。
 * 扣回统一 {@link #reverse}：取消点赞 / 取消收藏 / 下架的 sourceKey 固定（原入账终身一次，扣回也只能一次）；
 * 退款（Story 1.3）用 {@code "REFUND:" + refundKey}，最终形如 {@code REV:123:REFUND:rf_xxx}。
 * {@code SPEND:*} / {@code ADMIN:*} / {@code BATCH:*} / {@code BACKFILL:*} 由后续 story 追加到本类。
 */
public final class GrowthIdemKeys {

    /** 与 {@code growth_ledger.idem_key VARCHAR(160)} 一致。 */
    public static final int MAX_LENGTH = 160;

    public static final String SOURCE_UNLIKE = "UNLIKE";
    public static final String SOURCE_UNCOLLECT = "UNCOLLECT";
    public static final String SOURCE_TAKEDOWN = "TAKEDOWN";

    private GrowthIdemKeys() {
    }

    public static String dailyLogin(long userId, LocalDate wibDate) {
        return "DAILY_LOGIN:" + userId + ":" + wibDate;
    }

    public static String post(long postId) {
        return "POST:" + postId;
    }

    public static String commentPosted(long commentId) {
        return "COMMENT_POSTED:" + commentId;
    }

    public static String commentReceived(long commentId) {
        return "COMMENT_RECEIVED:" + commentId;
    }

    public static String likeGiven(long likerId, long postId) {
        return "LIKE_GIVEN:" + likerId + ":" + postId;
    }

    public static String likeReceived(long likerId, long postId) {
        return "LIKE_RECEIVED:" + likerId + ":" + postId;
    }

    public static String collectReceived(long collectorId, long postId) {
        return "COLLECT_RECEIVED:" + collectorId + ":" + postId;
    }

    public static String checkin(long checkinId) {
        return "CHECKIN:" + checkinId;
    }

    /** 账号级里程碑（无宠物）petId 记 0。 */
    public static String milestone(long userId, String code, Long petId) {
        return "MILESTONE:" + userId + ":" + code + ":" + (petId == null ? 0L : petId);
    }

    public static String giftSent(long giftOrderId) {
        return "GIFT_SENT:" + giftOrderId;
    }

    public static String giftReceived(long giftOrderId) {
        return "GIFT_RECEIVED:" + giftOrderId;
    }

    /** 反向流水：{@code REV:{原流水 id}:{sourceKey}}。 */
    public static String reverse(long originalLedgerId, String sourceKey) {
        return "REV:" + originalLedgerId + ":" + sourceKey;
    }
}
