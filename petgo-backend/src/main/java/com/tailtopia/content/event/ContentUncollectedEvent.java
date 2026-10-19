package com.tailtopia.content.event;

/**
 * 帖子被取消收藏领域事件（V1.4.0，过去式）。「我的 → Disimpan」里移除不可见条目<b>不发</b>本事件（④ AC11c）。
 *
 * <p>本类由 Story 0.1 地基定义：发布方 ④（{@code feat/1.4.0-push-collect}）、消费方 ①（{@code COLLECT_RECEIVED} 扣回）
 * <b>只引用、不重定义、不改字段</b>。
 *
 * @param postId      被取消收藏的帖子 id
 * @param collectorId 取消收藏的人 id
 * @param authorId    帖子作者 id
 */
public record ContentUncollectedEvent(long postId, long collectorId, long authorId) {
}
