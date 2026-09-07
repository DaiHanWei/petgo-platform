package com.tailtopia.shop.review.dto;

import com.tailtopia.shop.review.domain.ShopReview;
import java.time.Instant;
import java.util.List;

/**
 * 一条评价（Story 7.3 详情页评价区 · 7.2 我的评价）。
 *
 * <p>🔒 <b>不下发评价者身份</b>：详情页只需要星级、文字、图、时间。
 * 下发昵称/头像会让「谁买了什么」变成公开信息 —— 而宠物用品的购买记录本身带健康暗示。
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
        String reviewStatus) {

    public static ShopReviewView published(ShopReview r, List<String> imageUrls) {
        return new ShopReviewView(r.getId(), r.getRating(), r.getContent(), imageUrls,
                r.getCreatedAt(), null);
    }

    public static ShopReviewView mine(ShopReview r, List<String> imageUrls) {
        return new ShopReviewView(r.getId(), r.getRating(), r.getContent(), imageUrls,
                r.getCreatedAt(), r.getReviewStatus().name());
    }
}
