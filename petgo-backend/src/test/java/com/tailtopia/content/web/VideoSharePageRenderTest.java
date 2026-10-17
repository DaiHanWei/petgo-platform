package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.MediaType;
import com.tailtopia.content.dto.SharedPostResponse;
import com.tailtopia.content.dto.VideoView;
import com.tailtopia.content.service.ContentShareService;
import com.tailtopia.content.service.PostSharePageAnalytics;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * L0：专题线 video Story 2.6 —— 内容分享 H5 页（{@code content_share.html}）对视频帖的渲染：
 * 封面 + 播放角标 + 时长 + 「需更新到最新版 App 观看」，网页内不播放（不出 {@code <video>}、不出视频地址）；
 * 图文帖与改动前一致。Controller 用 mock 服务直接调，模板用独立 Thymeleaf 引擎直渲（无 Spring 上下文）。
 */
class VideoSharePageRenderTest {

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

    private static final String VIDEO_URL = "https://v.cdn.test/video/7/secret-key.mp4";

    private static SharedPostResponse video() {
        return new SharedPostResponse("Official", null, false, "KNOWLEDGE", "Kenapa kucing menginjak?",
                List.of("https://cdn.test/public/7/cover.jpg"), Instant.parse("2026-10-16T03:00:00Z"),
                MediaType.VIDEO, new VideoView(VIDEO_URL, 48_000, 720, 1280));
    }

    private static SharedPostResponse image() {
        return new SharedPostResponse("Budi", null, false, "DAILY", "Halo",
                List.of("https://cdn.test/public/8/a.jpg", "https://cdn.test/public/8/b.jpg"),
                Instant.parse("2026-10-16T03:00:00Z"), MediaType.IMAGE, null);
    }

    private static String render(SharedPostResponse post, ExtendedModelMap model) {
        ContentShareService service = mock(ContentShareService.class);
        when(service.findSharedPost(any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.of(post));
        ContentSharePageController controller = new ContentSharePageController(service,
                mock(PostSharePageAnalytics.class), "https://dl.test", "https://apps.test", "https://play.test");
        String view = controller.sharedPost("tok", model, new MockHttpServletRequest(), new MockHttpServletResponse());
        Context ctx = new Context(Locale.forLanguageTag("id"));
        ctx.setVariables(model);
        return engine.process(view, ctx);
    }

    @Test
    void videoPostShowsCoverBadgeDurationAndUpgradeNoticeButNeverPlays() {
        ExtendedModelMap model = new ExtendedModelMap();
        String html = render(video(), model);

        assertThat(model.get("isVideo")).isEqualTo(true);
        assertThat(model.get("hasImages")).isEqualTo(false);
        assertThat(html)
                .contains("id=\"videoCover\"")
                .contains("https://cdn.test/public/7/cover.jpg")
                .contains("0:48")
                .contains("id=\"videoNotice\"")
                .contains("Tonton di aplikasi")
                .contains("tailtopia://post/tok");
        assertThat(html).as("网页内不播放，视频地址不进页面").doesNotContain("<video").doesNotContain(VIDEO_URL);
    }

    @Test
    void imagePostIsUnchanged() {
        ExtendedModelMap model = new ExtendedModelMap();
        String html = render(image(), model);

        assertThat(model.get("isVideo")).isEqualTo(false);
        assertThat(model.get("hasImages")).isEqualTo(true);
        assertThat(model.get("downloadCta")).isEqualTo("Buat arsip untuk hewanku");
        assertThat(html).doesNotContain("videoCover").doesNotContain("videoNotice")
                .contains("https://cdn.test/public/8/a.jpg").contains("https://cdn.test/public/8/b.jpg");
    }

    @Test
    void durationLabelIsMinutesColonSeconds() {
        assertThat(ContentSharePageController.durationLabel(75_000)).isEqualTo("1:15");
        assertThat(ContentSharePageController.durationLabel(48_000)).isEqualTo("0:48");
        assertThat(ContentSharePageController.durationLabel(null)).isEqualTo("0:00");
    }
}
