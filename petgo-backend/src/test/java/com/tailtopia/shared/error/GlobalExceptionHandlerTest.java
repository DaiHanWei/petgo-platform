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

    /** 真实 MessageSource（三语 bundle），使「挂了文案码的异常按 locale 输出」这条也能在此断言。 */
    private static com.tailtopia.shared.i18n.Messages messages() {
        return new com.tailtopia.shared.i18n.Messages(
                new com.tailtopia.shared.i18n.AdminLocaleConfig().messageSource());
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PingErrorController())
            .setControllerAdvice(new GlobalExceptionHandler(messages()))
            .build();

    /**
     * 权限拒绝（@PreAuthorize → AccessDeniedException/AuthorizationDeniedException）必须原样重抛
     * 交还 Spring Security 按 403 处理，绝不允许落入 catch-all 被伪装成 500（生产事故：后台建兽医
     * 无 vet.create 报「服务暂时不可用」）。
     */
    @Test
    void accessDeniedIsRethrownNotSwallowedAs500() {
        var handler = new GlobalExceptionHandler(messages());
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

    /**
     * 挂了文案码的 AppException 按当前 locale 输出 detail；未挂码的原样输出原文。
     * 这条护住「给后台异常挂码不会改变 App 侧看到的文案」这个前提——api 请求无后台 locale cookie，
     * 回落 zh_CN，与外化前逐字相同。
     */
    @org.junit.jupiter.api.Test
    void localizedDetailFollowsLocaleAndFallsBackToRawMessage() {
        var messages = messages();
        var plain = AppException.validation("原样中文");
        org.assertj.core.api.Assertions.assertThat(messages.resolve(plain)).isEqualTo("原样中文");

        var coded = AppException.validation("显示名不能为空").code("admin.err.account.displayNameRequired");
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        try {
            org.assertj.core.api.Assertions.assertThat(messages.resolve(coded)).isEqualTo("显示名不能为空");
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("id"));
            org.assertj.core.api.Assertions.assertThat(messages.resolve(coded))
                    .isNotEqualTo("显示名不能为空")
                    .doesNotStartWith("admin.err.");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }
}
