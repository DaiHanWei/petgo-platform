package com.tailtopia.content.event;

/**
 * 帖子被收藏领域事件（V1.4.0，过去式）。收藏人就是作者本人时照常发出，由消费方决定是否计分。
 *
 * <p>本类由 Story 0.1 地基定义：发布方 ④（{@code feat/1.4.0-push-collect}）、消费方 ①（{@code COLLECT_RECEIVED} 成长值）
 * 与 1.4.2「第一次收藏内容」里程碑<b>只引用、不重定义、不改字段</b>（字段已在 ① PRD §6.4、④ PRD §6.4 约定）。
 *
 * @param postId      被收藏帖子 id
 * @param collectorId 收藏人 id
 * @param authorId    帖子作者 id
 */
public record ContentCollectedEvent(long postId, long collectorId, long authorId) {
}
