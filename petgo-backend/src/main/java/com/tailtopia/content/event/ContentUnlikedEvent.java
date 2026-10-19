package com.tailtopia.content.event;

/**
 * 内容被取消点赞领域事件（V1.4.0 ① Story 1.1 · 架构 AD-7，过去式）。仅在真删到点赞行且非自赞时发布
 * （未赞时取消、自赞取消都不发）；内容删除级联清点赞<b>不发</b>（作者自删帖不扣成长值，PRD）。
 *
 * <p>消费方：① 扣回 {@code LIKE_GIVEN} / {@code LIKE_RECEIVED}。
 *
 * @param postId   被取消点赞的内容 id
 * @param likerId  取消点赞的人 id
 * @param authorId 内容作者 id
 */
public record ContentUnlikedEvent(long postId, long likerId, long authorId) {
}
