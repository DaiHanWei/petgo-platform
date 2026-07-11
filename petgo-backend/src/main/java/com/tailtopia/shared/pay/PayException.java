package com.tailtopia.shared.pay;

/**
 * 支付网关调用异常（Story 1.1）。网关超时 / 非 2xx / 不可解析时抛出。
 *
 * <p>护栏：message 仅携<b>不含凭证 / 签名 / 上游堆栈 / 回调正文</b>的简短安全原因，绝不外泄。
 */
public class PayException extends RuntimeException {

    public PayException(String message) {
        super(message);
    }

    public PayException(String message, Throwable cause) {
        super(message, cause);
    }
}
