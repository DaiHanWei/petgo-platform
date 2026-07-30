package com.tailtopia.shared.error;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * 业务异常基类。携带 HTTP 状态、ProblemDetail type 与对用户安全的 detail 文案。
 * 由 {@link GlobalExceptionHandler} 统一转换为 RFC 9457 ProblemDetail，绝不外泄堆栈。
 */
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;

    public AppException(HttpStatus status, URI type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public URI getType() {
        return type;
    }

    // 常用工厂 —— 语义化构造，对齐架构 HTTP 状态码表
    public static AppException validation(String detail) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.VALIDATION, detail);
    }

    public static AppException notFound(String detail) {
        return new AppException(HttpStatus.NOT_FOUND, ErrorTypes.NOT_FOUND, detail);
    }

    public static AppException forbidden(String detail) {
        return new AppException(HttpStatus.FORBIDDEN, ErrorTypes.FORBIDDEN, detail);
    }

    public static AppException conflict(String detail) {
        return new AppException(HttpStatus.CONFLICT, ErrorTypes.CONFLICT, detail);
    }

    public static AppException unauthorized(String detail) {
        return new AppException(HttpStatus.UNAUTHORIZED, ErrorTypes.UNAUTHORIZED, detail);
    }

    public static AppException rateLimited(String detail) {
        return new AppException(HttpStatus.TOO_MANY_REQUESTS, ErrorTypes.RATE_LIMITED, detail);
    }

    /** Story 2.1：媒体凭证/签名 URL 签发失败（上游 OSS/STS 异常），对外 502，绝不外泄原始错误。 */
    public static AppException mediaCredential(String detail) {
        return new AppException(HttpStatus.BAD_GATEWAY, ErrorTypes.MEDIA_CREDENTIAL, detail);
    }

    /** Story 2.2：单账号单宠物，已存在档案再建（409）。 */
    public static AppException profileExists(String detail) {
        return new AppException(HttpStatus.CONFLICT, ErrorTypes.PROFILE_EXISTS, detail);
    }

    /** Story 2.3 R2（F10）：发布审核——文字命中违规（422，不落库，停编辑页可重提）。 */
    public static AppException contentTextBlocked(String detail) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.CONTENT_TEXT_BLOCKED, detail);
    }

    /** Story 2.3 R2（F10）：发布审核——图像命中违规（422，不落库，停编辑页可重提）。 */
    public static AppException contentImageBlocked(String detail) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.CONTENT_IMAGE_BLOCKED, detail);
    }

    /**
     * 内容审核 story 3：评论发送同步过滤命中（L1 硬拦截或风险 ≥0.8，422）。从未落库、不发事件、不入队；
     * 前端按 error type 映射单一本地化 toast（不展示 detail 原文，RFC9457 护栏）。
     */
    public static AppException commentBlocked(String detail) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.COMMENT_BLOCKED, detail);
    }
}
