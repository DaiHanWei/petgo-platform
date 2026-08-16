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

    /** bug 20260806：PawCoin 余额不足（409）。专属 type——409 在支付链路多因复用（支付窗过期/守卫不符），
     * 前端须按 type 精确映射「余额不足」文案，不能只看 status。 */
    public static final URI PAWCOIN_INSUFFICIENT = URI.create(BASE + "pawcoin-insufficient");

    /** Story 1.1（V1.1.4）：请求他人主页但已主动拉黑对方（403）。专属 type——前端须据此把
     * 「已拉黑」与「网络失败」两种不弹卡的情况区分开（UI 稿 A4 / A5 两个不同 Toast），
     * 不能混为一谈。响应体不含被拉黑者的任何展示字段。 */
    public static final URI BLOCKED_USER = URI.create(BASE + "blocked-user");

    private ErrorTypes() {
    }
}
