package com.tailtopia.auth.dto;

/**
 * 他人迷你主页投影（Story 3.8，FR-26）。Jackson NON_NULL。
 *
 * <p>V1 仅 nickname/avatar/signature/postCount + isDeactivated——**无关注数、无主页帖列表**（防前端误用）。
 * 已注销用户 {@code isDeactivated=true}、nickname/avatar/signature 为 null（前端据此不弹卡，NFR-8）。
 *
 * @param nickname     昵称（注销时 null）
 * @param avatarUrl    头像（注销时 null）
 * @param signature    个性签名（用户自填 ≤60 字；未设置 / 注销时 null。2026-08-07 用户反馈：
 *                     设了签名的用户，别人点进这张卡应当看得到）。**注销必须为 null**（NFR-8 匿名化）。
 * @param postCount    已发布（未软删）内容数
 * @param isDeactivated 是否已注销
 * @param tags         运营标签（V1.1.6 Story 5.1 · FR-74）。最多 3 个；注销时为空表
 */
public record MiniProfileResponse(
        String nickname,
        String avatarUrl,
        String signature,
        long postCount,
        boolean isDeactivated,
        java.util.List<UserTagView> tags) {

    public static MiniProfileResponse deactivated() {
        return new MiniProfileResponse(null, null, null, 0, true, java.util.List.of());
    }

    public static MiniProfileResponse of(AuthorView author, String signature, long postCount) {
        return new MiniProfileResponse(author.nickname(), author.avatarUrl(), signature, postCount,
                false, author.tags().isEmpty() ? null : author.tags());
    }
}
