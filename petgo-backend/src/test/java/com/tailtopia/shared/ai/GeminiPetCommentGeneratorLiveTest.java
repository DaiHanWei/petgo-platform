package com.tailtopia.shared.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 看图写评论实链路（打真实 gemini-2.5-flash），用来本地试提示词效果。无 key / 无图时自动跳过，CI 不跑。
 *
 * <pre>
 * GEMINI_API_KEY=xxx \
 * AUTO_COMMENT_TEST_IMAGE_URL=https://tailtopia.oss-ap-southeast-5.aliyuncs.com/public/.../xxx.jpg \
 * AUTO_COMMENT_TEST_POST_TEXT="Main di taman" AUTO_COMMENT_TEST_SPECIES=DOG AUTO_COMMENT_TEST_PET_NAME=Mochi \
 * ./mvnw -B -Dtest=GeminiPetCommentGeneratorLiveTest test
 * </pre>
 *
 * 按帖子 id 试真实数据：用后台 {@code GET /admin/auto-comment/preview?postId=123}（超管，不发评论不留档）。
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AUTO_COMMENT_TEST_IMAGE_URL", matches = ".+")
class GeminiPetCommentGeneratorLiveTest {

    @Test
    void generatesIndonesianCommentWithQuestion() {
        GeminiProperties props = new GeminiProperties();
        props.setMode("live");
        props.setApiKey(System.getenv("GEMINI_API_KEY"));
        props.setTimeoutSeconds(30);
        PetCommentResult r = new GeminiPetCommentGenerator(props).generate(
                System.getenv("AUTO_COMMENT_TEST_IMAGE_URL"),
                System.getenv("AUTO_COMMENT_TEST_POST_TEXT"),
                System.getenv("AUTO_COMMENT_TEST_SPECIES"),
                System.getenv("AUTO_COMMENT_TEST_PET_NAME"));

        System.out.println("skip=" + r.skip() + " comment=" + r.comment() + " skipReason=" + r.skipReason());
        if (!r.skip()) {
            assertThat(r.comment()).isNotBlank().hasSizeLessThanOrEqualTo(200);
            // 口径是「末尾以提问为主」：提示词要求问号收尾，模型偶尔把问句放中间，不算失败。
            assertThat(r.comment()).contains("?");
        }
    }
}
