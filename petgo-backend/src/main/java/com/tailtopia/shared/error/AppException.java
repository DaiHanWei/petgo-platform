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
    /** 可空的本地化文案码；为空则展示 {@link #getMessage()} 原文。见 {@link #code}。 */
    private String messageCode;
    private Object[] messageArgs = EMPTY_ARGS;

    private static final Object[] EMPTY_ARGS = new Object[0];

    public AppException(HttpStatus status, URI type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    /**
     * 挂一个本地化文案码，供管理后台按当前语言展示（{@code com.tailtopia.shared.i18n.Messages#resolve}）。
     *
     * <p><b>原文照留，不是替换。</b>构造时传入的中文仍是 {@code getMessage()}，继续充当日志文案、
     * 单测断言目标，以及取不到 code 时的兜底。这样加码是纯增量的：现有调用点与断言中文的测试全部不受影响。
     *
     * <pre>{@code
     * throw AppException.validation("超级管理员已达上限 " + CAP + " 个")
     *         .code("admin.err.account.superAdminCap", CAP);
     * }</pre>
     *
     * @param code 文案码（三语键集由 L0 对齐测试保证）
     * @param args MessageFormat 占位符实参，对应文案里的 {@code {0}}、{@code {1}}……
     * @return this（便于 {@code throw ...code(...)} 一行写完）
     */
    public AppException code(String code, Object... args) {
        this.messageCode = code;
        this.messageArgs = args == null ? EMPTY_ARGS : args;
        return this;
    }

    /** 本地化文案码；未挂码时为 {@code null}。 */
    public String getMessageCode() {
        return messageCode;
    }

    public Object[] getMessageArgs() {
        return messageArgs.clone();
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

    /** bug 20260806：PawCoin 余额不足——专属 type 供前端精确映射文案（409 多因复用，status 不够分流）。 */
    public static AppException pawcoinInsufficient(String detail) {
        return new AppException(HttpStatus.CONFLICT, ErrorTypes.PAWCOIN_INSUFFICIENT, detail);
    }

    public static AppException unauthorized(String detail) {
        return new AppException(HttpStatus.UNAUTHORIZED, ErrorTypes.UNAUTHORIZED, detail);
    }

    public static AppException rateLimited(String detail) {
        return new AppException(HttpStatus.TOO_MANY_REQUESTS, ErrorTypes.RATE_LIMITED, detail);
    }

    /** Story 3.4：下游/上游（如腾讯 IM 建会话）暂不可用（503，事务已回滚，用户可安全重试）。 */
    public static AppException serviceUnavailable(String detail) {
        return new AppException(HttpStatus.SERVICE_UNAVAILABLE, ErrorTypes.INTERNAL, detail);
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
