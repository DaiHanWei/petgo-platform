package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.service.ContentModerationService.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：发布审核 stub 放行/文字拦截/图片拦截三路径（AC8 · F10）。 */
class ContentModerationServiceTest {

    private final ContentModerationService moderation = new ContentModerationService();

    @Test
    void cleanContentPasses() {
        assertThat(moderation.moderate("Hari ini jalan-jalan ke taman bareng anabul",
                List.of("https://cdn.petgo.test/a.jpg")))
                .isEqualTo(Verdict.PASS);
    }

    @Test
    void nullContentPasses() {
        assertThat(moderation.moderate(null, null)).isEqualTo(Verdict.PASS);
    }

    @Test
    void blockedKeywordReturnsTextBlocked() {
        assertThat(moderation.moderate("ayo main judi online", null)).isEqualTo(Verdict.TEXT_BLOCKED);
    }

    @Test
    void keywordMatchIsCaseInsensitive() {
        // 占位关键词大小写不敏感（子串匹配）。
        assertThat(moderation.moderate("This is a SCAM offer", null)).isEqualTo(Verdict.TEXT_BLOCKED);
    }

    @Test
    void markedImageReturnsImageBlocked() {
        assertThat(moderation.moderate("teks normal",
                List.of("https://cdn.petgo.test/moderation-blocked-x.jpg")))
                .isEqualTo(Verdict.IMAGE_BLOCKED);
    }

    @Test
    void textCheckedBeforeImage() {
        // 文字与图片都违规时，文字优先（与 AC8「文字先过再审图像」一致）。
        assertThat(moderation.moderate("judi", List.of("moderation-blocked")))
                .isEqualTo(Verdict.TEXT_BLOCKED);
    }
}
