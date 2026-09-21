/**
 * @ 提及（V1.3.0 batch-b1 · Epic 3 · FR-119 · AD-10）。
 *
 * <p>本模块只管**候选集**这一件事：维护「最近互动过的 N 个人」，并按 owner 取出来。
 *
 * <h2>🛡 模块边界</h2>
 * <ul>
 *   <li>与 {@code content} 之间**只经领域事件**（{@code ContentLikedEvent} /
 *       {@code ContentCommentedEvent}），<b>不直读 content 的任何仓储</b>；</li>
 *   <li>拉黑关系只走 {@code social.read.UserHideRelationReader}
 *       —— AD-7：四处共用同一个出口，禁各写各的查询；</li>
 *   <li>昵称 / 头像只经 {@code auth.service.AccountQueryService} 取（不直 join users 表）。</li>
 * </ul>
 *
 * <h2>🔴 没有全局用户搜索</h2>
 * 候选接口<b>不接受任意关键词查全库</b>（AD-10 Rule 3 / Story 3.1 AC5）。
 * 搜索留在 1.6.0，<b>未前移</b>。界面上那个输入框不是搜索框。
 */
package com.tailtopia.mention;
