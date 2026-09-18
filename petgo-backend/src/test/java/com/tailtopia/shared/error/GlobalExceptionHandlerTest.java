package com.tailtopia.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    /** 与 application.yml 的 spring.servlet.multipart.max-file-size 同值——超限文案里的数字取自它。 */
    private static GlobalExceptionHandler handler() {
        return new GlobalExceptionHandler(messages(),
                org.springframework.util.unit.DataSize.ofMegabytes(10));
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PingErrorController())
            .setControllerAdvice(handler())
            .build();

    /**
     * 权限拒绝（@PreAuthorize → AccessDeniedException/AuthorizationDeniedException）必须原样重抛
     * 交还 Spring Security 按 403 处理，绝不允许落入 catch-all 被伪装成 500（生产事故：后台建兽医
     * 无 vet.create 报「服务暂时不可用」）。
     */
    @Test
    void accessDeniedIsRethrownNotSwallowedAs500() {
        var handler = handler();
        var denied = new org.springframework.security.authorization.AuthorizationDeniedException("Access Denied");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handleAccessDenied(denied))
                .isSameAs(denied);
    }

    /**
     * 上传超出 multipart 上限 → 413 且 detail 里带**具体 MB 数**。
     *
     * <p>护的是一次真实事故：{@code spring.servlet.multipart.max-file-size} 没配，Boot 默认 1MB，
     * Tomcat 在进 controller 之前就抛 {@code MaxUploadSizeExceededException} →
     * 落进 catch-all → 500「服务暂时不可用」。运营看到的是一句与体积无关的话，
     * 而 AdminSeedImageService 那道 10MB 校验与它的文案**永远走不到**。
     */
    @Test
    void oversizedUploadIsPayloadTooLargeWithConcreteLimit() {
        var req = new org.springframework.mock.web.MockHttpServletRequest(
                "POST", "/admin/shop/banners/images");
        var pd = handler().handleUploadTooLarge(
                new org.springframework.web.multipart.MaxUploadSizeExceededException(10L * 1024 * 1024),
                req);
        org.assertj.core.api.Assertions.assertThat(pd.getStatusCode().value()).isEqualTo(413);
        org.assertj.core.api.Assertions.assertThat(pd.getBody()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(pd.getBody().getDetail()).contains("10");
    }

    /**
     * 🔴 2026-09-03 stag 回归 P1：10.2MB 图 POST 到 {@code /admin/shop/banners/images}
     * 回的是 <b>500</b>「服务暂时不可用」，不是上面那条 413 —— 上一条用例全程绿着，
     * 因为它喂的是 Spring 的 {@code MaxUploadSizeExceededException}，而线上抛的根本不是它。
     *
     * <p>真实形态：Tomcat 自己的 {@code FileSizeLimitExceededException}（继承 {@code IOException}，
     * 与 Spring 的 multipart 异常体系无血缘）。{@code /admin/**} 那条链带 CSRF 过滤器，
     * 它提前读参数、就地触发 multipart 解析 —— DispatcherServlet 还没接手，
     * 没人把它包成 Spring 的类型，于是掉进 catch-all。
     *
     * <p>⚠️ 本用例特意走 {@code handleUnexpected}（catch-all）而不是 {@code handleUploadTooLarge}：
     * <b>那才是线上实际走到的分支</b>。喂对了入口，用例才有意义。
     */
    @Test
    void tomcatRawSizeExceptionIsAlso413NotUnhandled500() {
        var req = new org.springframework.mock.web.MockHttpServletRequest(
                "POST", "/admin/shop/banners/images");

        // 单文件超限（stag 实测那条）
        var oneFile = new org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException(
                "The field file exceeds its maximum permitted size of 10485760 bytes.",
                10_700_000L, 10_485_760L);
        assert413(handler().handleUnexpected(oneFile, req));

        // 整请求超限
        var whole = new org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException(
                "the request was rejected because its size exceeds the configured maximum",
                30_000_000L, 20_971_520L);
        assert413(handler().handleUnexpected(whole, req));

        // 被容器包一层再抛上来（异常链里认，不看最外层类型）
        assert413(handler().handleUnexpected(new jakarta.servlet.ServletException(oneFile), req));
    }

    @SuppressWarnings("unchecked")
    private static void assert413(Object out) {
        var res = (org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail>) out;
        org.assertj.core.api.Assertions.assertThat(res.getStatusCode().value())
                .as("上传超限必须是 413，掉进 500 运营就只会反复重传同一张图")
                .isEqualTo(413);
        org.assertj.core.api.Assertions.assertThat(res.getBody()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(res.getBody().getDetail())
                .as("文案要带具体 MB 数，否则运营不知道该压到多小")
                .contains("10");
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

    // ================================================================
    // Story 5-2：用错请求方法 / Content-Type 不再返回 500（SHOP-FR-31）
    //
    // 🔴 本组守的是**告警噪声**，不只是状态码。只改状态码而日志仍是 catch-all 的
    //    log.error(..., ex) + 整条堆栈，告警一点没少 —— 需求等于没做完。
    // ================================================================

    /**
     * 🔴 用<b>真实 MockMvc 分发</b>而不是直接调 handler 方法：
     * 直接调只能证明「这个方法能构造出 ProblemDetail」，证明不了
     * <b>advice 真的接得住</b> Spring 在进 controller 之前抛出的那个异常。
     */
    @Test
    void wrongHttpMethodIs405WithAllowHeader() throws Exception {
        // PingErrorController 只声明了 GET /api/v1/_ping-error，对它 POST 即触发。
        mockMvc.perform(post("/api/v1/_ping-error"))
                .andExpect(status().isMethodNotAllowed())
                // HTTP 语义要求 405 必须带 Allow。Spring 的 DefaultHandlerExceptionResolver
                // 本来会自动加，但本类的 catch-all 把所有异常都截走了，那条路径永远走不到。
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.title").value("Method Not Allowed"))
                .andExpect(jsonPath("$.detail").value("请求方法不被支持"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.instance").value("/api/v1/_ping-error"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void methodNotAllowedBodyLeaksNothing() throws Exception {
        String body = mockMvc.perform(post("/api/v1/_ping-error"))
                .andReturn().getResponse().getContentAsString();

        // 绝不外泄堆栈 / 类名 / 包名 —— 它们对客户端无用，只暴露实现。
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("HttpRequestMethodNotSupportedException")
                .doesNotContain("org.springframework")
                .doesNotContain("at com.tailtopia")
                .doesNotContain("Exception");
    }

    @Test
    void unsupportedContentTypeIs415WithAcceptHeader() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new MediaTypeTestController())
                .setControllerAdvice(handler())
                .build();

        mvc.perform(post("/api/v1/_media-test")
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().exists("Accept"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.title").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.detail").value("不支持的请求内容类型"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.instance").value("/api/v1/_media-test"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void unsupportedMediaTypeBodyLeaksNothing() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new MediaTypeTestController())
                .setControllerAdvice(handler())
                .build();

        String body = mvc.perform(post("/api/v1/_media-test")
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("HttpMediaTypeNotSupportedException")
                .doesNotContain("org.springframework")
                .doesNotContain("Exception");
    }

    /**
     * 🔴 信封里<b>只有</b> base(...) 的六项，没有多余属性。
     *
     * <p>多一个属性就是多一处可能外泄实现细节的地方，而这类响应是**任何人**
     * 随便发一个请求就能拿到的。
     */
    @Test
    void methodNotAllowedEnvelopeHasExactlyTheSixFields() throws Exception {
        String body = mockMvc.perform(post("/api/v1/_ping-error"))
                .andReturn().getResponse().getContentAsString();

        var map = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, java.util.Map.class);
        org.assertj.core.api.Assertions.assertThat(map.keySet())
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance",
                        "traceId");
    }
}
