package com.tailtopia.admin.warmreply.dto;

import java.time.Instant;

/**
 * 帖子评论分布列表行（V1.3.0 Story 4.1 AC3）。
 *
 * @param postId        帖子 id（页内深链 {@code /admin/content?open=<id>}；「去评论」按钮 data-post-id）
 * @param summary       正文前 40 字
 * @param authorId      作者 id
 * @param authorName    作者昵称（注销 / 缺失回退 {@code #id}）
 * @param authorVirtual 作者是否虚拟账号池（{@code account_type = 'VIRTUAL'}，后台可见标识）
 * @param createdAt     发布时刻（UTC；模板按 WIB 显示）
 * @param commentCount  可见评论数（一级 + 二级；{@code deleted_at IS NULL AND moderation_status = 'VISIBLE'}）
 * @param virtualCount  其中虚拟账号发的可见评论数
 */
public record DistributionRow(long postId, String summary, long authorId, String authorName, boolean authorVirtual,
        Instant createdAt, long commentCount, long virtualCount) {
}
