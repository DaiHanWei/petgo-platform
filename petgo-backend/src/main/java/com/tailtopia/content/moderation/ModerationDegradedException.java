package com.tailtopia.content.moderation;

/**
 * 三方审核降级信号（内容审核 Story 1 · 方案 §4.3）。{@code ContentSafetyClient} 在超时 / 4xx / 5xx /
 * 配额耗尽 / 熔断打开时抛出，由 {@code ContentModerationService.evaluate} 统一映射为 {@code DEGRADED} 结果。
 *
 * <p><b>核心不变量</b>：捕获此异常绝不返回 PASS —— fail-closed，交调用方（story 2/3）转人工队列。
 * <p>护栏：message 仅携带不含原文 / 图 URL / AK / 上游堆栈的简短原因。
 */
public class ModerationDegradedException extends RuntimeException {

    private final DegradeReason reason;

    public ModerationDegradedException(DegradeReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ModerationDegradedException(DegradeReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public DegradeReason reason() {
        return reason;
    }
}
