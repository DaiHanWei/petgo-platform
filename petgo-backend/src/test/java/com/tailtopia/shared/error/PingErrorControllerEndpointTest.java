package com.tailtopia.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * {@link PingErrorController} 端点集成测试（dev only 诊断端点，AC2 错误信封）：
 * {@code GET /api/v1/_ping-error}。
 *
 * <p>该端点放行（permitAll）后必抛 {@link AppException#validation}，经 {@link GlobalExceptionHandler}
 * 转 RFC 9457 ProblemDetail（422 UNPROCESSABLE_ENTITY）。断言：
 * <ul>
 *   <li>content-type = {@code application/problem+json}；</li>
 *   <li>信封字段 type / title / status / detail / instance / traceId 齐全且值正确。</li>
 * </ul>
 */
class PingErrorControllerEndpointTest extends ApiIntegrationTest {

    /** 命中即抛 → 统一 ProblemDetail 信封（422 + problem+json + 全字段）。 */
    @Test
    void pingError_returnsRfc9457ProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/_ping-error"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://petgo/errors/validation"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("这是用于验证错误信封的占位错误（dev only）"))
                .andExpect(jsonPath("$.instance").value("/api/v1/_ping-error"))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
