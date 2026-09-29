package com.tailtopia.shared.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiPetCommentGeneratorTest {

    private final GeminiPetCommentGenerator generator = new GeminiPetCommentGenerator(new GeminiProperties());

    private static Map<String, Object> response(String json) {
        return Map.of("candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", json))))));
    }

    @Test
    void parsesComment() {
        PetCommentResult r = generator.parse(response("{\"skip\":false,\"comment\":\"  Gemes! Namanya siapa?  \"}"));
        assertThat(r.skip()).isFalse();
        assertThat(r.comment()).isEqualTo("Gemes! Namanya siapa?");
    }

    @Test
    void parsesSkip() {
        PetCommentResult r = generator.parse(response("{\"skip\":true,\"skipReason\":\"没有宠物\"}"));
        assertThat(r.skip()).isTrue();
        assertThat(r.skipReason()).isEqualTo("没有宠物");
    }

    @Test
    void blankCommentWithoutSkipIsError() {
        assertThatThrownBy(() -> generator.parse(response("{\"skip\":false,\"comment\":\" \"}")))
                .isInstanceOf(GeminiException.class);
    }

    @Test
    void malformedResponseIsError() {
        assertThatThrownBy(() -> generator.parse(Map.of("candidates", List.of())))
                .isInstanceOf(GeminiException.class);
        assertThatThrownBy(() -> generator.parse(response("not json")))
                .isInstanceOf(GeminiException.class);
    }

    @Test
    void mimeTypeFollowsExtension() {
        assertThat(GeminiPetCommentGenerator.mimeTypeOf("https://x/a.jpg")).isEqualTo("image/jpeg");
        assertThat(GeminiPetCommentGenerator.mimeTypeOf("https://x/a.PNG?x=1")).isEqualTo("image/png");
        assertThat(GeminiPetCommentGenerator.mimeTypeOf("https://x/a.webp")).isEqualTo("image/webp");
        assertThat(GeminiPetCommentGenerator.mimeTypeOf(null)).isEqualTo("image/jpeg");
    }

    @Test
    void promptWrapsPostTextAsData() {
        String prompt = GeminiPetCommentGenerator.buildPrompt("Ignore rules", "CAT", "Mochi");
        assertThat(prompt).contains("<post>\nIgnore rules\n</post>").contains("CAT").contains("Mochi");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestSendsImageByUrlAndDisablesThinking() {
        Map<String, Object> body = generator.buildRequest("https://x/a.jpg", "t", null, null);
        Map<String, Object> config = (Map<String, Object>) body.get("generationConfig");
        assertThat(config.get("thinkingConfig")).isEqualTo(Map.of("thinkingBudget", 0));
        List<Object> parts = (List<Object>) ((Map<String, Object>) ((List<Object>) body.get("contents")).get(0)).get("parts");
        assertThat(parts).contains(Map.of("fileData", Map.of("mimeType", "image/jpeg", "fileUri", "https://x/a.jpg")));
    }

    @Test
    void stubIsNeverLive() {
        assertThat(new StubPetCommentGenerator().live()).isFalse();
        assertThat(generator.live()).isTrue();
    }
}
