package com.tailtopia.shared.error;

import java.net.URI;

/**
 * RFC 9457 ProblemDetail 的 {@code type} URI 常量。
 * 各业务模块抛 {@link AppException} 时引用，保证全平台错误 type 一致、可枚举。
 */
public final class ErrorTypes {

    private static final String BASE = "https://petgo/errors/";

    public static final URI VALIDATION = URI.create(BASE + "validation");
    public static final URI UNAUTHORIZED = URI.create(BASE + "unauthorized");
    public static final URI FORBIDDEN = URI.create(BASE + "forbidden");
    public static final URI NOT_FOUND = URI.create(BASE + "not-found");
    public static final URI CONFLICT = URI.create(BASE + "conflict");
    public static final URI RATE_LIMITED = URI.create(BASE + "rate-limited");
    public static final URI INTERNAL = URI.create(BASE + "internal");

    /** Story 2.1：媒体凭证 / 签名 URL 签发异常（绝不外泄 OSS 原始错误/堆栈）。 */
    public static final URI MEDIA_CREDENTIAL = URI.create(BASE + "media-credential");

    /** Story 2.2：单账号单宠物——已存在档案时再建（409）。 */
    public static final URI PROFILE_EXISTS = URI.create(BASE + "profile-exists");

    /** Story 2.3 R2（F10）：发布时三方自动审核——文字关键词命中违规（422，不落库）。 */
    public static final URI CONTENT_TEXT_BLOCKED = URI.create(BASE + "content-text-blocked");

    /** Story 2.3 R2（F10）：发布时三方自动审核——图像识别命中违规（422，不落库）。 */
    public static final URI CONTENT_IMAGE_BLOCKED = URI.create(BASE + "content-image-blocked");

    /** 内容审核 story 3：评论发送同步过滤命中（L1 硬拦截 / 风险 ≥0.8，422，从未落库/不发事件/不入队）。 */
    public static final URI COMMENT_BLOCKED = URI.create(BASE + "comment-blocked");

    private ErrorTypes() {
    }
}
