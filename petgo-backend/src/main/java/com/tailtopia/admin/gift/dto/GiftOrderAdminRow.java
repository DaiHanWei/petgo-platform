package com.tailtopia.admin.gift.dto;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * 后台送礼记录的一行（V1.4.0 ③ gift Story 3.2 · AB-36）：全站列表、异常单视图、导出与用户抽屉「收送礼记录」共用。
 *
 * <p>🔴 展示一律取<b>订单快照</b>（NFR2 / AD-2）：礼物名 {@code gift_name_en / gift_name_id}、宠物名 {@code pet_name}；
 * 不读 {@code gift_catalog} / {@code pet_profiles} 当前值。{@code petDeleted} = {@code pet_id IS NULL}（宠物已删 / 主人已注销）。
 *
 * <p>🔴 送礼人 / 主人已注销（{@code users.deleted_at IS NOT NULL}）时 {@code senderName / ownerName} 为 {@code null}，
 * 页面与导出显示「（已注销）#id」，<b>不显示注销前快照昵称</b>（AC6.1）。
 *
 * <p>🔴 不带 {@code payment_intent_id} 数字（不外露自增 id）：QRIS 单只带 {@code payment_intents.public_token}；
 * 不带 {@code refund_error}（可能含内部异常类名，AC3.1）。
 *
 * @param intentToken QRIS 单的支付意图对外 token；PawCoin 单为 {@code null}
 */
public record GiftOrderAdminRow(
        Instant createdAt,
        long senderId,
        String senderName,
        boolean senderDeleted,
        long ownerId,
        String ownerName,
        boolean ownerDeleted,
        String petName,
        boolean petDeleted,
        String giftNameEn,
        String giftNameId,
        long unitPrice,
        int quantity,
        long total,
        String channel,
        String status,
        String undeliveredReason,
        int refundAttempts,
        Instant refundedAt,
        String intentToken) {

    /** 「已付款未送达」三个状态（异常单，AC2.1 / AC3）。 */
    public static final Set<String> ANOMALY_STATUSES =
            Set.of("UNDELIVERED_REFUND_PENDING", "UNDELIVERED_REFUNDED", "REFUND_MANUAL");

    /** 状态徽章的文案组：三个异常状态都显示「已付款未送达」（AC2.2）。 */
    public String statusGroup() {
        return ANOMALY_STATUSES.contains(status) ? "UNDELIVERED" : status;
    }

    /** 状态徽章色：已送达绿 / 待支付中性 / 已过期·支付失败灰 / 已付款未送达黄（AC2.2）。 */
    public String statusBadge() {
        return switch (statusGroup()) {
            case "DELIVERED" -> "badge-ok";
            case "PENDING" -> "badge-neutral";
            case "UNDELIVERED" -> "badge-warn";
            default -> "badge-muted";
        };
    }

    /** 礼物名按界面语言取快照：印尼文取 ID 名，中文 / 英文取 EN 名（AC2.2）。 */
    public String giftName(Locale locale) {
        String lang = locale == null ? "" : locale.getLanguage();
        return "id".equals(lang) || "in".equals(lang) ? giftNameId : giftNameEn;
    }

    /** payment_intent 缩写：前 6 + {@code …} + 后 3（AC2.2）；PawCoin 单 / 无 token → {@code null}（页面显示「—」）。 */
    public String intentShort() {
        if (intentToken == null || intentToken.isBlank()) {
            return null;
        }
        if (intentToken.length() <= 9) {
            return intentToken;
        }
        return intentToken.substring(0, 6) + "…" + intentToken.substring(intentToken.length() - 3);
    }

    /** 总价 {@code Rp{千分位逗号}}（后台金额惯例，与摘要条同口径）。 */
    public String totalText() {
        return "Rp" + String.format(Locale.ROOT, "%,d", total);
    }

    /** 已重试次数文本（传给 MessageFormat 用字符串）。 */
    public String refundAttemptsText() {
        return String.valueOf(refundAttempts);
    }

    /** 未送达原因是否为已知三值（未知值页面原样显示代码，AC3.1）。 */
    public boolean knownReason() {
        return "PET_DELETED".equals(undeliveredReason) || "OWNER_DELETED".equals(undeliveredReason)
                || "SENDER_BLOCKED".equals(undeliveredReason);
    }

    /** 送礼人 id 文本（传给 MessageFormat 时用字符串，免得被格式化成「10,233」）。 */
    public String senderIdText() {
        return String.valueOf(senderId);
    }

    /** 宠物主人 id 文本（同 {@link #senderIdText()}）。 */
    public String ownerIdText() {
        return String.valueOf(ownerId);
    }
}
