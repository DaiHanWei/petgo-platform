package com.tailtopia.auth.dto;

/**
 * 他人迷你主页投影（Story 3.8，FR-26）。Jackson NON_NULL。
 *
 * <p>V1 仅 nickname/avatar/postCount + isDeactivated——**无关注数、无主页帖列表**（防前端误用）。
 * 已注销用户 {@code isDeactivated=true}、nickname/avatar 为 null（前端据此不弹卡，NFR-8）。
 *
 * @param nickname     昵称（注销时 null）
 * @param avatarUrl    头像（注销时 null）
 * @param postCount    已发布（未软删）内容数
 * @param isDeactivated 是否已注销
 */
public record MiniProfileResponse(
        String nickname,
        String avatarUrl,
        long postCount,
        boolean isDeactivated) {

    public static MiniProfileResponse deactivated() {
        return new MiniProfileResponse(null, null, 0, true);
    }

    public static MiniProfileResponse of(AuthorView author, long postCount) {
        return new MiniProfileResponse(author.nickname(), author.avatarUrl(), postCount, false);
    }
}
