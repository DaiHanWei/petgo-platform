package com.tailtopia.auth.domain;

/**
 * 用户标签徽章的底色（2026-08-28，UI 稿 `.utag-icon`）。
 *
 * <p>稿子里同一个圆形徽章按标签取不同底色：官方账号用金色（CSS 默认值），
 * 「最佳新人」这类用紫色（内联覆盖 {@code background:var(--tt-violet)}）。
 * 此前实现只有一个写死的金色，运营配不出第二种。
 *
 * <p>🔴 **固定调色板，不给自由填 hex**。理由不是洁癖：
 * 徽章里的图标按规范是**纯白剪影**，底色必须深到白色看得见。
 * 放开填色，运营迟早会配出一个浅色底 —— 那时图标"消失"，而它和
 * 「图片没加载出来」长得一模一样（{@code TagIcon} 失败时收缩为零），
 * 又是一轮查不出原因的排查。这个坑本月已经踩过一次。
 *
 * <p>⚠️ 落库存**枚举名**（架构约定：枚举落库 varchar + UPPER_SNAKE），
 * 对外下发**十六进制色值** —— 客户端因此不必认识调色板，
 * 将来加一档颜色不需要发版。
 */
public enum UserTagBadgeColor {

    /** 金 —— UI 稿的默认值（官方账号 ✓ 用的就是它）。 */
    GOLD("#F6A609"),
    /** 紫 —— UI 稿里「最佳新人 ★」用的那档。 */
    VIOLET("#845EC9"),
    /** 深紫。 */
    VIOLET_DEEP("#6C48AE"),
    /** 珊瑚红。 */
    CORAL("#F0425A"),
    /** 绿。 */
    GREEN("#1F9E6A"),
    /** 蓝。 */
    BLUE("#5B9BD5");

    private final String hex;

    UserTagBadgeColor(String hex) {
        this.hex = hex;
    }

    /** 对外下发的色值，形如 {@code #F6A609}。 */
    public String hex() {
        return hex;
    }

    /** 宽松解析：空 / 不认识 → {@link #GOLD}（稿子的默认值），绝不抛。 */
    public static UserTagBadgeColor parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return GOLD;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GOLD;
        }
    }
}
