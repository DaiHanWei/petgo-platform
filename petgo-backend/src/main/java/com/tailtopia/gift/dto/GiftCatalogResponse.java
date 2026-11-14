package com.tailtopia.gift.dto;

import java.util.List;

/**
 * App 礼物目录（V1.4.0 gift Story 1.1 · AC4 · AD-10）：{@code GET /api/v1/gifts/catalog}。
 *
 * <p>只含 ONLINE 礼物；<b>不下发</b>目录内部 id、{@code sort_order} 与物种布尔值（排序已在服务端做完）。
 *
 * @param recipient       收礼宠物（{@code petId} 交给发起接口）
 * @param qrisMinAmount   送礼 QRIS 单笔最低金额（IDR；低于它 App 置灰 QRIS）
 * @param tiers           普通礼物，按价位升序分组
 * @param exclusiveGroups 已解锁的专属礼物，按解锁等级升序分组（第一批在前）
 * @param lockBlock       下一批未解锁专属礼物的门槛；全解锁（或没有专属礼物）为 null
 * @param categories      V1.5.0 gift-pack Story 1-1（D-3）：普通礼物按在线分类分组（{@code sort_order, id}），
 *                        没有在线礼物的分类不出现；旧版 App 仍用 {@code tiers}
 */
public record GiftCatalogResponse(Recipient recipient, long qrisMinAmount, List<Tier> tiers,
        List<ExclusiveGroup> exclusiveGroups, LockBlock lockBlock, List<Category> categories) {

    /** 收礼宠物（{@code petType} = CAT / DOG / OTHER；{@code avatarUrl} / {@code breed} 可空）。 */
    public record Recipient(long petId, String name, String avatarUrl, String petType, String breed) {
    }

    /** 一个价位档（组内已按「适用该物种 → 通用 → 其他」再 {@code sort_order} 排好）。 */
    public record Tier(long price, List<GiftItem> gifts) {
    }

    /** 一批专属礼物（同一解锁等级）。 */
    public record ExclusiveGroup(int minLevel, List<GiftItem> gifts) {
    }

    /** 一个分类（{@code categoryId} = {@code gift_categories.id}，参考数据例外 D-4；组内排序同 {@code tiers}）。 */
    public record Category(long categoryId, String nameEn, String nameId, List<GiftItem> gifts) {
    }

    /** 这只宠物收到过这件礼物的送礼人（最近送达倒序最多 3 人，{@code more} = 其余不同送礼人数）。 */
    public record ReceivedFrom(List<Sender> senders, int more) {
    }

    /** 送礼人（已注销：{@code nickname = null, deleted = true}）。 */
    public record Sender(String nickname, boolean deleted) {
    }

    /** 锁区块：送礼人还差的最低解锁等级。 */
    public record LockBlock(int requiredLevel) {
    }

    /**
     * 单个礼物。App 按账号语言取 {@code nameEn} / {@code nameId}（不渲染后端显示串）。
     *
     * @param giftToken 礼物 {@code public_token}（发起送礼用）
     * @param kind      NORMAL / EXCLUSIVE
     * @param minLevel  专属礼物解锁等级；普通礼物为 null
     * @param senderExp V1.5.0：送 1 个时送礼人可得 EXP（不参与成长体系 = 0）
     * @param receivedFrom V1.5.0：这只宠物收到过这件礼物时的送礼人；没收到过 = null
     */
    public record GiftItem(String giftToken, String nameEn, String nameId, String iconUrl, long price, String kind,
            Integer minLevel, int senderExp, ReceivedFrom receivedFrom) {
    }
}
