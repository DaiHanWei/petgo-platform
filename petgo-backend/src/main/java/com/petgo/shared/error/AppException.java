package com.petgo.shared.error;

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
}
