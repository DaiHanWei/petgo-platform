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

    /**
     * Story 1.1（V1.1.4）：请求他人主页但已主动拉黑对方（403）。
     *
     * <p>取 403 而非 404：语义是「你屏蔽了这个人」不是「这个人不存在」，且<b>不构成枚举泄漏</b>——
     * 能触发该分支的前提是调用者自己建立过拉黑关系，他本就知道对方存在。前端按 type 分流，不依赖 status。
     * <b>响应体不得含被拉黑者的任何展示字段</b>（AD-11：「200 + 标记字段」等于拦了一半）。
     */
    public static AppException blockedUser(String detail) {
        return new AppException(HttpStatus.FORBIDDEN, ErrorTypes.BLOCKED_USER, detail);
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

    /** V1.3.2 Story 1.5：发帖关联的打卡不存在或不是本人的（422）。 */
    public static AppException postCheckinInvalid(String detail) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.POST_CHECKIN_INVALID, detail);
    }

    /** V1.3.2 Story 1.1：不在场所 500m 内（422）。🔴 调用方只传固定文案，绝不拼距离值。 */
    public static AppException checkinTooFar(String detail) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.CHECKIN_TOO_FAR, detail);
    }

    /** V1.3.2 Story 1.1：今天（WIB）已在该场所打过卡（409）。 */
    public static AppException checkinAlreadyToday(String detail) {
        return new AppException(HttpStatus.CONFLICT, ErrorTypes.CHECKIN_ALREADY_TODAY, detail);
    }

    /** V1.3.2 Story 1.1：账号无宠物档案（422）。 */
    public static AppException checkinNoPet(String detail) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.CHECKIN_NO_PET, detail);
    }

    /** V1.3.2 Story 1.1：petIds 含非本人宠物（403）。 */
    public static AppException checkinPetForbidden(String detail) {
        return new AppException(HttpStatus.FORBIDDEN, ErrorTypes.CHECKIN_PET_FORBIDDEN, detail);
    }

    /** V1.3.2 Story 3.1：一次性解锁对象已解锁 / 已付款（409 {@code keepsake-already-unlocked}）。 */
    public static AppException keepsakeAlreadyUnlocked(String detail) {
        return new AppException(HttpStatus.CONFLICT, ErrorTypes.KEEPSAKE_ALREADY_UNLOCKED, detail);
    }

    /** V1.3.2 Story 3.3：佩戴未解锁结果 → 422 {@code tailsonality-badge-locked}。 */
    public static AppException tailsonalityBadgeLocked(String detail) {
        return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorTypes.TAILSONALITY_BADGE_LOCKED, detail);
    }
}
