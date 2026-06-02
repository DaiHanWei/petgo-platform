package com.petgo.content.dto;

import com.petgo.content.domain.ContentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 发布内容请求（{@code POST /api/v1/content-posts}）。author 取自 JWT，不在 DTO。
 *
 * @param type      内容类型（DAILY/GROWTH_MOMENT/KNOWLEDGE），必填
 * @param petId     成长日历绑定的宠物 id（仅 GROWTH_MOMENT 需要，且须属当前用户）
 * @param text      正文 ≤1000
 * @param imageUrls 公开桶 CDN URL 列表 ≤9（经 Story 2.1 直传得到）
 */
public record ContentPostCreateRequest(
        @NotNull(message = "内容类型不能为空") ContentType type,
        Long petId,
        @Size(max = 1000, message = "正文不能超过 1000 字") String text,
        @Size(max = 9, message = "最多 9 张图片") List<@Size(max = 1024) String> imageUrls) {
}
