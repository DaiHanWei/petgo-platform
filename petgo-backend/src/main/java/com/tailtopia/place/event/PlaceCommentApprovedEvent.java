package com.tailtopia.place.event;

import java.time.Instant;

/**
 * 场所评论审核通过领域事件（V1.3.4 follow · Story 3.2 · AD-9，过去式）。供 notify 发「有人评论了你添加的地点」。
 *
 * <p>只在评论 {@code UNDER_REVIEW → VISIBLE} 那一刻发（与可见计数 +1 同一分支，重复 approve 不重发）；
 * 场所已不对用户可见（下架 / 合并 / 软删）或无创建者时不发（T-35）。
 * 比 AD-9 的 {@code (placeId, commentId, authorId)} 多带 {@code placeToken}（通知 / 推送的 targetRef）与
 * {@code placeCreatorId}（接收者），让 notify 侧<b>不必读 place 仓储</b>。
 *
 * <p>🔴 <b>不带评论正文</b>：场所评论通知不展示评论内容（FR-141.12）。
 *
 * @param placeId        场所 id（合并键 {@code COMMENT:PLACE:{placeId}}）
 * @param placeToken     场所对外 token
 * @param placeCreatorId 场所创建者 users.id（接收者）
 * @param commentId      场所评论 id（{@code place_comments.id}，与内容评论 id 空间独立）
 * @param commenterId    评论人 users.id（触发人）
 * @param approvedAt     审核通过时刻（UTC）
 */
public record PlaceCommentApprovedEvent(long placeId, String placeToken, long placeCreatorId, long commentId,
        long commenterId, Instant approvedAt) {
}
