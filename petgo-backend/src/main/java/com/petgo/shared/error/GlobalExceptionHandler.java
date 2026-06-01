package com.petgo.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理 —— 所有异常统一输出 RFC 9457 ProblemDetail。
 * 字段：type / title / status / detail / instance / traceId（校验错误附 errors）。
 * 强制护栏：绝不外泄堆栈；5xx 不回显内部细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> handleApp(AppException ex, HttpServletRequest req) {
        ProblemDetail pd = base(ex.getStatus(), ex.getType(), titleFor(ex.getStatus()), ex.getMessage(), req);
        return ResponseEntity.status(ex.getStatus()).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        ProblemDetail pd = base(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.VALIDATION, "Validation Failed",
                "请求参数校验未通过", req);
        List<FieldErrorEntry> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorEntry(fe.getField(), fe.getDefaultMessage()))
                .toList();
        pd.setProperty("errors", errors);
        return ResponseEntity.unprocessableEntity().body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest req) {
        // 5xx：服务端记录完整堆栈，对外仅给通用文案 + traceId，绝不外泄内部细节
        String traceId = currentTraceId();
        log.error("Unhandled exception [traceId={}]", traceId, ex);
        ProblemDetail pd = base(HttpStatus.INTERNAL_SERVER_ERROR, ErrorTypes.INTERNAL, "Internal Server Error",
                "服务暂时不可用，请稍后重试", req, traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }

    private ProblemDetail base(HttpStatus status, URI type, String title, String detail, HttpServletRequest req) {
        return base(status, type, title, detail, req, currentTraceId());
    }

    private ProblemDetail base(HttpStatus status, URI type, String title, String detail, HttpServletRequest req,
            String traceId) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(type);
        pd.setTitle(title);
        if (req != null) {
            pd.setInstance(URI.create(req.getRequestURI()));
        }
        pd.setProperty("traceId", traceId);
        return pd;
    }

    private static String currentTraceId() {
        String fromMdc = MDC.get("traceId");
        return fromMdc != null ? fromMdc : UUID.randomUUID().toString();
    }

    private static String titleFor(HttpStatus status) {
        return switch (status) {
            case UNPROCESSABLE_ENTITY, BAD_REQUEST -> "Validation Failed";
            case UNAUTHORIZED -> "Unauthorized";
            case FORBIDDEN -> "Forbidden";
            case NOT_FOUND -> "Not Found";
            case CONFLICT -> "Conflict";
            case TOO_MANY_REQUESTS -> "Rate Limited";
            default -> status.getReasonPhrase();
        };
    }

    /** 校验错误的字段条目（camelCase JSON）。 */
    public record FieldErrorEntry(String field, String message) {
    }
}
