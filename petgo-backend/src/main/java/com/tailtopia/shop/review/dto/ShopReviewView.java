package com.tailtopia.shop.review.dto;

import com.tailtopia.shop.review.domain.ShopReview;
import java.time.Instant;
import java.util.List;

/**
 * 一条评价（Story 7.3 详情页评价区 · 7.2 我的评价）。
 *
 * <p>🔒 Story 7.3 原口径「不下发评价者身份」；v1.4.2 shop Story 6.1 按决策日志 C-9（UI B1「Dewi S.」）补
 * {@code nickname}（<b>服务端打码后</b>的昵称「首词 + 末词首字母.」，见 {@code ReviewerNameMasker}；注销 / 封号 → null；<b>不下发头像、用户 token</b>）与
 * {@code specName}（订单行规格快照）。两字段只在详情页公开列表有值，「我的评价」为 null（NON_NULL 下省略）。
 *
 * <p>🔴 <b>首版不做追评、不做商家回复</b>（自营模式下「商家回复」即平台回复，
 * 价值低于运营成本），故本视图没有对应字段。
 */
public record ShopReviewView(
        long id,
        int rating,
        String content,
        List<String> imageUrls,
        Instant createdAt,
        /** 仅「我的评价」用；详情页列表恒为 null（那里只出已发布的）。 */
        String reviewStatus,
        /** v1.4.2 C-9：评价者昵称（打码后，如「Dewi S.」；注销 / 封号 → null）；仅公开列表。 */
        String nickname,
        /** v1.4.2 C-9：所评订单行的规格名快照；仅公开列表。 */
        String specName) {

    public static ShopReviewView published(ShopReview r, List<String> imageUrls) {
        return published(r, imageUrls, null, null);
    }

    public static ShopReviewView published(ShopReview r, List<String> imageUrls, String nickname,
            String specName) {
        return new ShopReviewView(r.getId(), r.getRating(), r.getContent(), imageUrls,
                r.getCreatedAt(), null, nickname, specName);
    }

    public static ShopReviewView mine(ShopReview r, List<String> imageUrls) {
        return new ShopReviewView(r.getId(), r.getRating(), r.getContent(), imageUrls,
                r.getCreatedAt(), r.getReviewStatus().name(), null, null);
    }
}
