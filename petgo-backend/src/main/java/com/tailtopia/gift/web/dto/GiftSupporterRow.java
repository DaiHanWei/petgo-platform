package com.tailtopia.gift.web.dto;

import java.util.List;

/**
 * 粉丝榜一行（V1.4.0 gift Story 2.1 · AC2.2）。一人一行；<b>不下发</b> {@code sender_id}、{@code users.public_token}、
 * 任何自增 id（UI 稿 E1 粉丝行不可点）。
 *
 * @param rank        名次，从 1 连续递增、跨页连续（同额不并列，同额已按最近送礼时间定序）
 * @param nickname    昵称；送礼人已注销为 null（NON_NULL 省略），App 本地化「已注销用户」
 * @param avatarUrl   头像；注销为 null
 * @param deleted     送礼人是否已注销
 * @param totalAmount 该送礼人累计送给这只宠物的金额（IDR，只含已送达；decision-log ③ Q1 主人与访客均可见）
 * @param giftCount   累计个数
 * @param gifts       送过的礼物（按种类 × 价位聚合），单价降序、数量降序取前 3 种
 * @param moreKinds   其余种类数。<b>恒下发</b>，没有更多时为 0（App 据此显示「+N」）
 */
public record GiftSupporterRow(
        int rank,
        String nickname,
        String avatarUrl,
        boolean deleted,
        long totalAmount,
        long giftCount,
        List<GiftKind> gifts,
        int moreKinds) {

    /** 送过的某种礼物（快照名称 EN / ID 两份 + 图标 + 个数）。 */
    public record GiftKind(String iconUrl, String nameEn, String nameId, long count) {
    }
}
