package com.tailtopia.social.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 关注 / 粉丝列表的一行（V1.3.4 follow · Story 2.2 · AD-4）；Story 2.3 推荐列表复用。
 *
 * <p>关系字段均<b>相对当前查看者</b>（游客全 false）。注销行 {@code deleted=true}，
 * {@code nickname / avatarUrl / userToken} 为 null（NON_NULL 省略）。
 *
 * @param userId      对方数字 id（跳现有主页路由用）
 * @param userToken   对方不可枚举 token（关注操作用，D-2）
 * @param iFollow     我是否关注对方
 * @param followsMe   对方是否关注我
 * @param blockedByMe 我是否主动拉黑了对方（只认 BLOCK，T-20）—— 该行不显示关注按钮
 * @param self        这一行是不是我自己
 */
public record FollowListItem(
        long userId,
        String userToken,
        String nickname,
        String avatarUrl,
        boolean deleted,
        // ⚠️ 显式键名：`iFollow` 这类单字母前缀的组件名要钉死，防序列化命名漂移。
        @JsonProperty("iFollow") boolean iFollow,
        @JsonProperty("followsMe") boolean followsMe,
        @JsonProperty("blockedByMe") boolean blockedByMe,
        @JsonProperty("self") boolean self) {
}
