package com.tailtopia.admin.aiquery.service;

/**
 * 提交被拒（可预期的业务结果，不是故障）：页面在输入框上方显示原因，并保留已输入内容（UX B2 / B6）。
 * 不走 {@code AppException}：那条路径对非 htmx 请求回 ProblemDetail JSON，而这里要回整页。
 */
public class AiQueryRejection extends RuntimeException {

    public enum Reason {
        /** AI_QUERY_MODE=off */
        DISABLED,
        /** 问题为空 */
        EMPTY,
        /** 超过 2000 字（args[0] = 当前字数） */
        TOO_LONG,
        /** 单人未完成任务已达 3 个 */
        LIMIT,
        /** 同一对话有未完成任务 */
        BUSY,
        /** 授权失效：联系管理员（Story 1.8） */
        AUTH_FAILED
    }

    private final Reason reason;
    private final transient Object[] args;

    public AiQueryRejection(Reason reason, Object... args) {
        super(reason.name(), null, false, false);
        this.reason = reason;
        this.args = args;
    }

    public Reason reason() {
        return reason;
    }

    public Object[] args() {
        return args;
    }

    public String messageKey() {
        return "admin.aiQuery.err." + switch (reason) {
            case DISABLED -> "disabled";
            case EMPTY -> "empty";
            case TOO_LONG -> "tooLong";
            case LIMIT -> "limit";
            case BUSY -> "busy";
            case AUTH_FAILED -> "authFailed";
        };
    }
}
