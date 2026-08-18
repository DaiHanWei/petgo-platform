package com.tailtopia.content.event;

import com.tailtopia.content.domain.DeleteReason;
import java.time.Instant;

/**
 * 内容**不再可展示**领域事件（V1.1.6 Story 4.1，过去式）。
 *
 * <h2>🔴 为什么不复用 {@link ContentRemovedEvent}</h2>
 * 那个事件只在**运营下架**（{@code ADMIN_TAKEDOWN}）时发布，而且"作者自删不发"是**刻意的** ——
 * 它被 notify 消费来推送「你发布的内容因违反社区规范已被移除」，作者自删也发的话，
 * 用户删自己的帖会收到一条说自己违规的通知。
 *
 * <p>但顶置联动需要的是**三种触发全覆盖**（作者删除 / 审核判违规下架 / 作者账号被封禁），
 * 语义是"这条内容没了，凡是引用它的展示位都该收手"，与"通知作者违规"完全不同。
 * 故新增本事件：<b>软删时无论什么原因都发</b>，{@link ContentRemovedEvent} 一行不改。
 *
 * <p>两者不是重复 —— 消费方不同、语义不同、触发条件不同。
 *
 * @param postId   内容 id
 * @param authorId 作者 id
 * @param reason   下架原因（消费方可据此区分，本版本顶置联动不区分）
 * @param at       发生时刻（UTC）
 */
public record ContentUnavailableEvent(long postId, long authorId, DeleteReason reason, Instant at) {
}
