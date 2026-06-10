package com.tailtopia.shared.ai;

/**
 * Gemini 调用可重试异常（Story 4.1）。超时 / 非 2xx / 响应不可解析时抛出，交 triage DB 状态机重试 ≤3。
 *
 * <p>护栏：message 仅携带<b>不含健康数据 / key / 签名 URL</b> 的简短原因，绝不外泄上游堆栈。
 */
public class GeminiException extends RuntimeException {

    public GeminiException(String message) {
        super(message);
    }

    public GeminiException(String message, Throwable cause) {
        super(message, cause);
    }
}
