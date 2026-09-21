package com.tailtopia.place.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * L0：场所分享 H5 与共用失效页的模板直渲（V1.3.0 batch-b1 Story 1.10 · UI 稿 A9 / A10）。
 *
 * <p>守两件事：① 场所失效页换成场所文案、<b>不再出现宠物护照字样</b>；
 * ② {@code card_gone} 被名片 / 里程碑 / 内容 / 异常兜底共用 —— 不传覆盖键时输出与原来逐字一致。
 */
class PlaceShareTemplateRenderTest {

    private static SpringTemplateEngine engine;

    @BeforeAll
    static void setUp() {
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        files.setCheckExistence(true);
        engine = new SpringTemplateEngine();
        engine.addTemplateResolver(files);
    }

    private static String render(String template, Map<String, Object> vars) {
        Context ctx = new Context(Locale.forLanguageTag("id"));
        ctx.setVariables(vars);
        return engine.process(template, ctx);
    }

    // ===== A10：场所失效页 =====

    @Test
    void placeGonePageUsesPlaceCopyAndNeverMentionsPaspor() {
        String html = render("card_gone", Map.of(
                "downloadUrl", "https://dl.test",
                "goneTitle", PlaceSharePageController.GONE_TITLE,
                "goneSubtitle", PlaceSharePageController.GONE_SUBTITLE,
                "goneCta", PlaceSharePageController.GONE_CTA));

        assertThat(html)
                .contains("Tempat ini sudah tidak ada")
                .contains("Tempat ini sudah dihapus atau tautannya tidak berlaku lagi.")
                .contains("Temukan tempat lain")
                .contains("noindex");
        assertThat(html)
                .as("场所失效页不得出现宠物护照文案")
                .doesNotContain("Paspor")
                .doesNotContain("Jelajahi hewan lain")
                .doesNotContain("Halaman ini");
    }

    /** 🛡 其它复用方不传覆盖键 → 原护照文案逐字保留（名片 / 里程碑 / 内容 / 异常兜底零影响）。 */
    @Test
    void otherCallersStillGetTheOriginalPassportCopy() {
        String html = render("card_gone", Map.of("downloadUrl", "https://dl.test"));

        assertThat(html)
                .contains("Halaman ini<br/>sudah tidak ada")
                .contains("Paspor hewan ini sudah dihapus atau tautannya tidak berlaku lagi.")
                .contains("Jelajahi hewan lain")
                .doesNotContain("Tempat ini");
    }

    /** GlobalExceptionHandler 的 H5 兜底可能连 downloadUrl 都不给 —— 也必须能渲染。 */
    @Test
    void goneTemplateRendersWithEmptyModel() {
        assertThat(render("card_gone", Map.of())).contains("Jelajahi hewan lain");
    }

    // ===== A9：正常页 =====

    private static Map<String, Object> placeModel(List<String> images, int morePhotos) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("ogTitle", "Kopi Kayu Manis");
        m.put("ogDescription", "Temanmu mengajak kamu nongkrong di sini! Jl. Senopati No.75");
        m.put("ogImage", null);
        m.put("hasOgImage", false);
        m.put("placeName", "Kopi Kayu Manis");
        m.put("typeLabel", "Kafe");
        m.put("typeEmoji", "☕");
        m.put("tagLabels", List.of("Area outdoor"));
        m.put("hasTags", true);
        m.put("addressText", "Jl. Senopati No.75");
        m.put("description", "");
        m.put("hasDescription", false);
        m.put("images", images);
        m.put("hasImages", !images.isEmpty());
        m.put("photoCount", images.size());
        m.put("thumbs", images.subList(0, Math.min(4, images.size())));
        m.put("hasThumbs", images.size() > 1);
        m.put("morePhotos", morePhotos);
        m.put("recommendCount", 12L);
        m.put("commentCount", 3L);
        m.put("deeplink", "tailtopia://place/tok");
        m.put("downloadCta", "Buka di TailTopia");
        m.put("downloadUrl", "https://dl.test");
        m.put("iosUrl", "https://ios.test");
        m.put("androidUrl", "https://play.test");
        return m;
    }

    @Test
    void sharePageRendersSocialProofDualCtaAndMoreOverlay() {
        List<String> six = List.of("https://cdn/0.jpg", "https://cdn/1.jpg", "https://cdn/2.jpg",
                "https://cdn/3.jpg", "https://cdn/4.jpg", "https://cdn/5.jpg");
        String html = render("place_share", placeModel(six, 3));

        assertThat(html)
                .contains("<b>12</b> rekomen")
                .contains("<b>6</b> foto")
                .contains("<b>3</b> komentar")
                .contains(">+3<")
                .contains("href=\"https://ios.test\"")
                .contains("href=\"https://play.test\"")
                .contains("Buka di TailTopia")
                .contains("data-deeplink=\"tailtopia://place/tok\"")
                .contains("☕ Kafe")
                .contains("Temanmu mengajak kamu nongkrong di sini!");
    }

    /** 无照片：渐变 + 🐾 兜底，页面上不出现任何 img 标签（不会裂图），也不列缩略。 */
    @Test
    void sharePageWithoutPhotosHasNoBrokenImage() {
        String html = render("place_share", placeModel(List.of(), 0));
        String body = html.substring(html.indexOf("<body"), html.indexOf("<script"));

        assertThat(body).doesNotContain("<img").doesNotContain("class=\"thumbs\"");
        assertThat(body).contains("class=\"heroph\"");
    }
}
