package com.tailtopia.shared.error;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅 dev profile 暴露的诊断端点 —— 用于验证 ProblemDetail 错误信封结构（AC2）。
 * 生产不存在。命中即抛 {@link AppException}，由 {@link GlobalExceptionHandler} 转 RFC 9457 输出。
 */
@RestController
@RequestMapping("/api/v1")
@Profile("dev")
public class PingErrorController {

    @GetMapping("/_ping-error")
    public void pingError() {
        throw AppException.validation("这是用于验证错误信封的占位错误（dev only）");
    }
}
