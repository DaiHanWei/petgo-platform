package com.tailtopia.gift.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 单笔礼物详情（V1.5.0 gift-pack Story 1-4 · AC3 · 架构 §4.1 {@code GiftOrderDetail}）。
 *
 * @param opened        仅主人视角；访客 null
 * @param alreadyOpened 仅拆礼物接口：本来就已拆；其余 null
 * @param message       祝福语；无 / 已隐藏为 null
 * @param messageHidden 祝福语被举报处理隐藏
 * @param ownerExp      仅主人视角：收礼 EXP（按面值现算，不入账，D-7）；访客 null
 * @param canReport     有祝福语 &amp;&amp; 未隐藏 &amp;&amp; 查看者 ≠ 送礼人
 */
public record GiftOrderDetail(String orderToken, long petId, String petName, String giftNameEn, String giftNameId,
        String giftIconUrl, int quantity, Instant receivedAt, Boolean opened, Boolean alreadyOpened, String message,
        boolean messageHidden, Sender sender, Integer ownerExp, boolean canReport) {

    /**
     * 送礼人。
     *
     * @param userId   仅送礼人未注销时给（App「Lihat profil」走 {@code /users/{id}}）
     * @param petNames 送礼人名下宠物名，最多 3 个，按建档时间
     */
    public record Sender(Long userId, String nickname, String avatarUrl, boolean deleted, List<String> petNames) {
    }
}
