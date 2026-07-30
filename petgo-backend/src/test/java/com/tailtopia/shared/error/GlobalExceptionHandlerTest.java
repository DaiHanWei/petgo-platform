package com.tailtopia.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * L0 测试（无需 DB/容器）：standalone MockMvc 验证 RFC 9457 ProblemDetail 信封结构。
 * 覆盖：status / type / title / instance / traceId 字段齐备，且不外泄堆栈。
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PingErrorController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /**
     * 权限拒绝（@PreAuthorize → AccessDeniedException/AuthorizationDeniedException）必须原样重抛
     * 交还 Spring Security 按 403 处理，绝不允许落入 catch-all 被伪装成 500（生产事故：后台建兽医
     * 无 vet.create 报「服务暂时不可用」）。
     */
    @Test
    void accessDeniedIsRethrownNotSwallowedAs500() {
        var handler = new GlobalExceptionHandler();
        var denied = new org.springframework.security.authorization.AuthorizationDeniedException("Access Denied");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handleAccessDenied(denied))
                .isSameAs(denied);
    }

    @Test
    void pingErrorReturnsProblemDetailEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/_ping-error"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/validation"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.instance").value("/api/v1/_ping-error"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.detail").exists());
    }
}
