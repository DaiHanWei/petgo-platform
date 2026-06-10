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
