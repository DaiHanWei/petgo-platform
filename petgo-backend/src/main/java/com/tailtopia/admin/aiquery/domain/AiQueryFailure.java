package com.tailtopia.admin.aiquery.domain;

/**
 * 失败原因的页面分类（spec FR9 / UX D1–D4）。由消息的 status + error_code 决定：
 * <ul>
 * <li>{@link #TIMEOUT}：ops 15 分钟超时（status=TIMEOUT）或 agent 10 分钟超时（AGENT_TIMEOUT）——警告色，可重试；</li>
 * <li>{@link #RATE_LIMITED}：订阅额度用尽（AGENT_RATE_LIMITED）——警告色，可重试；</li>
 * <li>{@link #AUTH_FAILED}：订阅 token 失效（AGENT_AUTH_FAILED）——危险色，联系管理员，不提供重试、输入框禁用；</li>
 * <li>{@link #FAILED}：其余（AGENT_ERROR / AGENT_LOST / AGENT_REJECTED …）——危险色，可重试。</li>
 * </ul>
 */
public enum AiQueryFailure {
    FAILED, TIMEOUT, RATE_LIMITED, AUTH_FAILED;

    public static AiQueryFailure of(AiQueryStatus status, String errorCode) {
        if (status == AiQueryStatus.TIMEOUT) {
            return TIMEOUT;
        }
        if (status != AiQueryStatus.FAILED) {
            return null;
        }
        if ("AGENT_TIMEOUT".equals(errorCode)) {
            return TIMEOUT;
        }
        if ("AGENT_RATE_LIMITED".equals(errorCode)) {
            return RATE_LIMITED;
        }
        if ("AGENT_AUTH_FAILED".equals(errorCode)) {
            return AUTH_FAILED;
        }
        return FAILED;
    }

    public boolean retryable() {
        return this != AUTH_FAILED;
    }

    /** 警告色（超时、额度用尽）还是危险色（失败、授权失效）。 */
    public boolean warn() {
        return this == TIMEOUT || this == RATE_LIMITED;
    }

    /** 文案 key 段：admin.aiQuery.fail.&lt;key&gt;.title / .note */
    public String key() {
        return switch (this) {
            case FAILED -> "failed";
            case TIMEOUT -> "timeout";
            case RATE_LIMITED -> "rateLimited";
            case AUTH_FAILED -> "authFailed";
        };
    }
}
