package com.tailtopia.social.dto;

import com.tailtopia.auth.dto.AuthorView;
import java.time.Instant;

/**
 * 黑名单页的一行（Story 1.5，FR-94）。
 *
 * <p><b>字段摊平、不嵌套 {@link AuthorView}</b> —— {@code AuthorView} 是内部投影，从不直接序列化出去
 * （与 {@code FeedItemResponse} 把作者摊成 {@code authorNickname/authorAvatarUrl/authorDeleted} 同款）。
 *
 * <p>⚠️ 全局 {@code default-property-inclusion: non_null}：注销用户的 {@code nickname}/{@code avatarUrl}
 * 为 null 时，<b>这两个键会整个从 JSON 里消失</b>（不是 {@code null} 值）。Dart 侧必须按可空键解析。
 *
 * <p><b>没有「是否被封号」这个字段，也不该有。</b> 封号（{@code UserStatus.DEACTIVATED}）是平台侧处置，
 * 只被登录/刷新门控读取，<b>不投影到任何对外 DTO</b>；被封号的人在黑名单里就该跟正常人长得一模一样。
 * 千万别把它和 {@link #deleted}（用户<b>自己注销</b>）搞混——两者要求正好相反（架构 S3 / 高风险点 R5）。
 *
 * @param userId    被拉黑者 id
 * @param nickname  昵称（注销时 null → 键消失）
 * @param avatarUrl 头像（注销时 null → 键消失）
 * @param deleted   对方是否<b>已自行注销</b>（不是封号）。true → 前端渲染匿名态且头像昵称去点击态，
 *                  但<b>「解除拉黑」按钮仍须可用</b>，用户得能清理名单
 * @param reported  同一对象是否<b>也被举报过</b>（存在 {@code source=REPORT} 的行）。
 *                  前端据此选「已举报」标签、解除确认正文与解除成功 Toast 的变体
 * @param blockedAt 主动拉黑的时间 —— 取 {@code BLOCK} 行的 {@code created_at}，
 *                  <b>不取 {@code updated_at}</b>，也不受事后追加 {@code REPORT} 行的影响（列表排序依据）
 */
public record BlockedUserItem(
        long userId,
        String nickname,
        String avatarUrl,
        boolean deleted,
        boolean reported,
        Instant blockedAt) {

    public static BlockedUserItem of(AuthorView author, boolean reported, Instant blockedAt) {
        return new BlockedUserItem(author.userId(), author.nickname(), author.avatarUrl(),
                author.deleted(), reported, blockedAt);
    }
}
