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
 * @param reported     当前查看者<b>是否举报过</b>此人（Story 2.1 AC8）。
 *                     <b>⚠️ 必须是装箱 {@code Boolean} 且游客时为 null</b> ——
 *                     全局 Jackson {@code NON_NULL} 会把 null 字段整个键省略掉，游客拿到的
 *                     key 集合因此<b>一字未变</b>（Story 1.1 AC6 的硬要求，L1 测试有三条断言守着）。
 *                     写成 primitive {@code boolean} 就永远出现在 JSON 里，当场破坏游客契约。
 * @param tags         运营标签（V1.1.6 Story 5.1 · FR-74）。最多 3 个；注销时为空表
 */
public record MiniProfileResponse(
        String nickname,
        String avatarUrl,
        String signature,
        long postCount,
        boolean isDeactivated,
        Boolean reported,
        java.util.List<UserTagView> tags) {

    /** 已注销：不暴露任何身份信息，也不下发「已举报」（对方都注销了，这个标记没有意义）。 */
    public static MiniProfileResponse deactivated() {
        return new MiniProfileResponse(null, null, null, 0, true, null, java.util.List.of());
    }

    /** @param reported 登录者传 true/false；<b>游客传 null</b>（键会被 NON_NULL 省略）。 */
    public static MiniProfileResponse of(AuthorView author, String signature, long postCount,
            Boolean reported) {
        return new MiniProfileResponse(author.nickname(), author.avatarUrl(), signature, postCount,
                false, reported, author.tags().isEmpty() ? null : author.tags());
    }
}
