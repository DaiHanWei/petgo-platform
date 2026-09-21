package com.tailtopia.mention.dto;

/**
 * @ 选择器里的一个人（V1.3.0 batch-b1 Story 3.1 · FR-119）。Jackson NON_NULL。
 *
 * <h2>⚠️ 只有三个字段，是刻意的</h2>
 * 这是一个<b>打字时弹出的选择列表</b>，一行只有头像 + 昵称。
 * 运营标签 / 签名 / 发帖数都<b>不在这里</b> —— 那是主页的事，往这里加只会让
 * 一次本该极快的取数多打几张表。
 *
 * <h2>🔴 {@code userId} 是必须的：正文里存 id 不存昵称（AD-10 Rule 4）</h2>
 * 存昵称的话，对方改名后所有历史 @ 全部失效、点不动，而且无法判断拉黑关系。
 * 客户端选中之后要把这个 id 带走。
 *
 * @param userId    候选人 id
 * @param nickname  当前昵称（渲染用；<b>不要持久化它</b>）
 * @param avatarUrl 头像，可空
 */
public record MentionCandidateView(long userId, String nickname, String avatarUrl) {
}
