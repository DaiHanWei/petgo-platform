package com.tailtopia.auth.dto;

/**
 * 作者展示投影（Story 3.2）。供 content Feed 经 service 取作者昵称/头像，**不让 content 直 join users 表**。
 *
 * <p>注销匿名化（NFR-8）：作者已注销时 {@code deleted=true}、{@code nickname}/{@code avatarUrl}=null，
 * 由前端渲染本地化「已注销用户」+ 默认头像，且头像不可点（Story 3.8 联动）。
 *
 * @param userId   作者 id（注销后仍返回，前端据 {@code deleted} 决定可点性）
 * @param nickname 昵称（注销时 null）
 * @param avatarUrl 头像（注销时 null）
 * @param deleted  是否已注销
 */
public record AuthorView(long userId, String nickname, String avatarUrl, boolean deleted) {

    public static AuthorView anonymized(long userId) {
        return new AuthorView(userId, null, null, true);
    }
}
