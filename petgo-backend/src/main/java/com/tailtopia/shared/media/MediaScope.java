package com.tailtopia.shared.media;

/**
 * 媒体隐私域（架构 §Data Architecture 媒体三层）。决定 STS 直传的目标桶与对象前缀。
 *
 * <ul>
 *   <li>{@link #PUBLIC}：公开桶①（Feed / 档案 / H5 名片图），经阿里 CDN 分发。前缀 {@code public/}。</li>
 *   <li>{@link #PRIVATE}：私密桶②（AI 分诊图 / 健康历史图），仅短 TTL 签名 URL 访问。前缀 {@code private/}。</li>
 * </ul>
 */
public enum MediaScope {
    PUBLIC("public"),
    PRIVATE("private");

    private final String prefix;

    MediaScope(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
