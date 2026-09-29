package com.tailtopia.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.autocomment.StagAutoCommentTool;
import com.tailtopia.shared.i18n.AdminLocaleConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** 【stag 分支专属】自动评论测试工具结果片段：用真实 Thymeleaf 引擎渲染，挡住只有运行时才暴露的模板语法错。 */
class AutoCommentStagResultTemplateTest {

    private static SpringTemplateEngine engine;

    @BeforeAll
    static void setUp() {
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        engine = new SpringTemplateEngine();
        engine.addTemplateResolver(files);
        engine.setTemplateEngineMessageSource(new AdminLocaleConfig().messageSource());
    }

    private static String render(StagAutoCommentTool.Result result) {
        Context ctx = new Context(Locale.SIMPLIFIED_CHINESE);
        ctx.setVariable("result", result);
        return engine.process("admin/fragments/auto-comment-stag-result", java.util.Set.of("result"), ctx);
    }

    @Test
    void rendersSummarySkipReasonsAndRows() {
        Map<String, Long> skipped = new LinkedHashMap<>();
        skipped.put("AI_SKIPPED", 1L);
        skipped.put("NO_IMAGE", 1L);
        String html = render(new StagAutoCommentTool.Result(true, 4, 1, 2, 1, skipped, List.of(
                new StagAutoCommentTool.Item(11, "POSTED", "Budi", "Lucu banget! Namanya siapa?", null, 5200L),
                new StagAutoCommentTool.Item(12, "AI_SKIPPED", "Sari", null, "图片里没有宠物", 3100L),
                new StagAutoCommentTool.Item(13, "NO_IMAGE", null, null, null, null),
                new StagAutoCommentTool.Item(14, "FAILED", "Budi", null, "GEMINI", 30000L)), 42_000));

        assertThat(html).contains("处理 4 条：成功 1 · 跳过 2 · 失败 1（用时 42 秒）");
        assertThat(html).contains("AI 跳过 × 1").contains("无图 × 1");
        assertThat(html).contains("Lucu banget! Namanya siapa?").contains("图片里没有宠物").contains("GEMINI");
        assertThat(html).contains("badge-ok").contains("badge-danger").contains("5200 ms");
    }

    @Test
    void rendersNotLiveBanner() {
        String html = render(new StagAutoCommentTool.Result(false, 0, 0, 0, 0, Map.of(), List.of(), 0));
        assertThat(html).contains("当前 AI 不是 live 模式");
    }

    @Test
    void rendersEmptyHint() {
        String html = render(new StagAutoCommentTool.Result(true, 0, 0, 0, 0, Map.of(), List.of(), 10));
        assertThat(html).contains("没有符合条件的帖子");
    }

    @Test
    void alreadyCommentedStatusHasLabel() {
        String html = render(new StagAutoCommentTool.Result(true, 1, 0, 1, 0, Map.of("ALREADY_COMMENTED", 1L),
                List.of(new StagAutoCommentTool.Item(15, "ALREADY_COMMENTED", null, null, null, null)), 100));
        assertThat(html).contains("期间已有人评论");
    }
}
