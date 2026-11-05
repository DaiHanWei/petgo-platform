/**
 * @ 提及（V1.3.0 batch-b1 · Epic 3 · FR-119 · AD-10）。
 *
 * <p>本模块只管**候选集**这一件事：维护「最近互动过的 N 个人」，并按 owner 取出来。
 * V1.4.2 social-topic Story 5.1 起范围扩大为「我关注 ∪ 关注我 ∪ 互动候选」（查询时并集，关注不写候选表）。
 *
 * <h2>🛡 模块边界</h2>
 * <ul>
 *   <li>与 {@code content} 之间**只经领域事件**（{@code ContentLikedEvent} /
 *       {@code ContentCommentedEvent}），<b>不直读 content 的任何仓储</b>；</li>
 *   <li>拉黑关系只走 {@code social.read.UserHideRelationReader}
 *       —— AD-7：四处共用同一个出口，禁各写各的查询；</li>
 *   <li>昵称 / 头像只经 {@code auth.service.AccountQueryService} 取（不直 join users 表）。</li>
 *   <li>⚠️ 有意例外：{@code repository.MentionScopeDao} 的范围原生 SQL 直读
 *       {@code user_follows} / {@code user_hide_relations} / {@code users} —— 上限须在排除之后仍成立
 *       （同 {@code UserFollowRepository.findSuggestionIds} 一类）；口径与 {@code hiddenEitherWay} /
 *       {@code isActive} 逐字一致，不引用 {@code social.repository} 的任何类。</li>
 * </ul>
 *
 * <h2>🔴 没有全局用户搜索</h2>
 * 候选接口<b>不查全库</b>：带关键词 {@code q} 时也只在上述三类范围内匹配，范围外的人搜不到
 * （AD-10 Rule 3 / V1.4.2 Story 5.1）。全局用户搜索归 1.5.4。
 */
package com.tailtopia.mention;
