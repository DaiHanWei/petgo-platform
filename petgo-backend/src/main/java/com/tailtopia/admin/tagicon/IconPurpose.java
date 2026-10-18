package com.tailtopia.admin.tagicon;

/**
 * 图标用途（V1.4.0 gift Story 3.1 · AC5）：同一套校验骨架（PNG / WebP、≤512KB、最短边 ≥42、OSS 公开对象），
 * 只差 OSS 子目录与报错 / 规格文案的键前缀。
 *
 * <p>🔴 只放「差在哪」，不放规则：规则只有 {@link AdminTagIconService} 一份，加用途时不得复制第二套校验。
 */
public enum IconPurpose {

    /** 用户标签 / 内容装饰标签（Story 11.5 起的既有行为，单参方法的缺省用途）。 */
    TAG("tag-icon", "admin.tagicon."),

    /** 礼物目录图标（V1.4.0 AB-35）：文案把「标签图标」换成「礼物图标」。 */
    GIFT("gift-icon", "admin.gifticon.");

    private final String folder;
    private final String keyPrefix;

    IconPurpose(String folder, String keyPrefix) {
        this.folder = folder;
        this.keyPrefix = keyPrefix;
    }

    /** OSS 公开目录下的子目录名（{@code public/<folder>/…}）。 */
    public String folder() {
        return folder;
    }

    /** 报错与规格说明的文案键前缀（以点结尾）。 */
    public String keyPrefix() {
        return keyPrefix;
    }
}
