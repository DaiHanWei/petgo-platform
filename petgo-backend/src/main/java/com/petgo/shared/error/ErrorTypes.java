package com.petgo.shared.error;

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

    private ErrorTypes() {
    }
}
