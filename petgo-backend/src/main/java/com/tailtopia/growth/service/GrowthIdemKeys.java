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

    /** 现金 / QRIS 消费（Story 1.3）：{@code SPEND:PI:{intentId}}。 */
    public static String spendIntent(long intentId) {
        return "SPEND:PI:" + intentId;
    }

    /** 纯 PawCoin 消费（Story 1.3）：{@code SPEND:COIN:{debit 的幂等键}}。 */
    public static String spendCoin(String debitIdempotencyKey) {
        return "SPEND:COIN:" + debitIdempotencyKey;
    }

    /** 纯 PawCoin 问诊（Story 1.3）：按问诊订单 id，与 {@link #spendCoin} 同一命名空间。 */
    public static String spendConsultCoin(long consultOrderId) {
        return spendCoin("vet-consult-order:" + consultOrderId);
    }

    /** 电商订单（Story 1.3，QRIS / MIXED / 纯币都只经此处）：{@code SPEND:SHOP:{shopOrderId}}。 */
    public static String spendShop(long shopOrderId) {
        return "SPEND:SHOP:" + shopOrderId;
    }

    /** 上线存量补算（Story 2.2；迁移 V20261018_1503 里以 SQL 写同一格式）：{@code BACKFILL:{u}}。 */
    public static String backfill(long userId) {
        return "BACKFILL:" + userId;
    }

    /** 退款扣回的 sourceKey（Story 1.3）：{@code REFUND:{refundKey}}，拼进 {@link #reverse}。 */
    public static String refundSource(String refundKey) {
        return "REFUND:" + refundKey;
    }

    /** 反向流水：{@code REV:{原流水 id}:{sourceKey}}。 */
    public static String reverse(long originalLedgerId, String sourceKey) {
        return "REV:" + originalLedgerId + ":" + sourceKey;
    }
}
