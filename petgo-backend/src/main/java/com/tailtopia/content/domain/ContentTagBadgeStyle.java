package com.tailtopia.content.domain;

/**
 * 内容标签胶囊的底色（2026-08-28，UI 稿 `.deco-badge`）。
 *
 * <p>稿子里胶囊底是一道 135° 的**双色渐变**（橙 → 红），此前写死在 App 里，
 * 运营只能配胶囊上的字与那枚小图 —— 于是「这枚胶囊」在他眼里只有一半是自己的。
 * 本枚举把底色也交出去。
 *
 * <p>🔴 **固定调色板，不给自由填色**。胶囊上的文字是**白色粗体 9.5px**：
 * 底色浅一点，那行字就读不出来了，而运营在后台看到的是自己电脑上的大图、
 * 察觉不到手机上 9.5px 的实际观感。每一档都已按"白字能读"挑过。
 *
 * <p>⚠️ 落库存**枚举名**（架构约定：枚举落库 varchar + UPPER_SNAKE），
 * 对外下发**两个十六进制色值**（渐变起止）—— 客户端因此不必认识调色板，
 * 将来加一档不需要发版。
 */
public enum ContentTagBadgeStyle {

    /** 橙 → 红 —— UI 稿 `.deco-badge` 的原始配色，也是默认值。 */
    SUNSET("#F6A609", "#F0596E"),
    /** 紫。 */
    VIOLET("#845EC9", "#6C48AE"),
    /** 珊瑚红。 */
    CORAL("#F0596E", "#C62B44"),
    /** 绿。 */
    GREEN("#1F9E6A", "#0E7A4D"),
    /** 蓝。 */
    BLUE("#5B9BD5", "#33689F"),
    /** 金。 */
    GOLD("#F6A609", "#C97F05"),
    /** 石墨灰 —— 给"中性/存档"类标签用，不抢内容风头。 */
    GRAPHITE("#5A5566", "#332F3D");

    private final String startHex;
    private final String endHex;

    ContentTagBadgeStyle(String startHex, String endHex) {
        this.startHex = startHex;
        this.endHex = endHex;
    }

    /** 渐变起点色值（135° 的左上端）。 */
    public String startHex() {
        return startHex;
    }

    /** 渐变终点色值（右下端）。 */
    public String endHex() {
        return endHex;
    }

    /** 供后台模板直接写进 style 的 CSS 渐变。 */
    public String css() {
        return "linear-gradient(135deg," + startHex + "," + endHex + ")";
    }

    /** 宽松解析：空 / 不认识 → {@link #SUNSET}（稿子的原始配色），绝不抛。 */
    public static ContentTagBadgeStyle parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SUNSET;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SUNSET;
        }
    }
}
