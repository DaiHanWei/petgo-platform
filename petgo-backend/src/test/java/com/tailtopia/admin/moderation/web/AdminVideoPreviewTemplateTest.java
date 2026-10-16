package com.tailtopia.admin.moderation.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * L0：专题线 video Story 3.3 —— 后台视频预览片段（{@code admin/fragments/video-preview :: player}）直渲：
 * 视频帖出 {@code <video>}，src / poster 为传入的签名地址；宿主按「视频帖才引入」的写法（与 drawer-content /
 * review-detail 同形：外层 th:block 判条件 —— th:replace 先于 th:if 执行）对图文帖不出 {@code <video>}。
 */
class AdminVideoPreviewTemplateTest {

    private static SpringTemplateEngine engine;

    /** 与 drawer-content / review-detail 同形的宿主：条件在外层 th:block，里层 th:replace。 */
    private static final String HOST = """
            <div id="host"><th:block th:if="${isVideo and videoUrl != null}"><div
                 th:replace="~{admin/fragments/video-preview :: player(${videoUrl}, ${posterUrl})}"></div></th:block>
            <img th:unless="${isVideo and videoUrl != null}" th:each="u : ${images}" th:src="${u}"/></div>
            """;

    @BeforeAll
    static void setUp() {
        StringTemplateResolver host = new StringTemplateResolver();
        host.setTemplateMode(TemplateMode.HTML);
        host.setOrder(2); // 兜底：文件解析器按存在性校验落空后，把宿主字符串当模板本身
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        files.setCheckExistence(true);
        files.setOrder(1);
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        engine = new SpringTemplateEngine();
        engine.addTemplateResolver(host);
        engine.addTemplateResolver(files);
        engine.setTemplateEngineMessageSource(messages);
    }

    private static String render(Map<String, Object> vars) {
        Context ctx = new Context(Locale.forLanguageTag("zh-CN"));
        ctx.setVariables(vars);
        return engine.process(HOST, ctx);
    }

    @Test
    void videoPostRendersPlayerWithSignedSrcAndPoster() {
        String html = render(Map.of("isVideo", true,
                "videoUrl", "https://signed.test/video/1/k.mp4?Signature=x",
                "posterUrl", "https://signed.test/public/1/c.jpg?Signature=y",
                "images", java.util.List.of("https://signed.test/public/1/c.jpg?Signature=y")));

        assertThat(html).contains("<video").contains("controls")
                .contains("src=\"https://signed.test/video/1/k.mp4?Signature=x\"")
                .contains("poster=\"https://signed.test/public/1/c.jpg?Signature=y\"")
                .contains("视频")
                .doesNotContain("<img");
    }

    @Test
    void missingPosterOmitsAttribute() {
        java.util.HashMap<String, Object> vars = new java.util.HashMap<>();
        vars.put("isVideo", true);
        vars.put("videoUrl", "https://signed.test/video/1/k.mp4?Signature=x");
        vars.put("posterUrl", null);
        vars.put("images", java.util.List.of());
        assertThat(render(vars)).contains("<video").doesNotContain("poster=");
    }

    @Test
    void imagePostRendersImagesOnly() {
        java.util.HashMap<String, Object> vars = new java.util.HashMap<>();
        vars.put("isVideo", false);
        vars.put("videoUrl", null);
        vars.put("posterUrl", null);
        vars.put("images", java.util.List.of("https://cdn.test/public/1/a.jpg"));
        assertThat(render(vars)).doesNotContain("<video").contains("src=\"https://cdn.test/public/1/a.jpg\"");
    }
}
