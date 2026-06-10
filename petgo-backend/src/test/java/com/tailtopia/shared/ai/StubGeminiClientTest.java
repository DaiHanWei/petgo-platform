package com.tailtopia.shared.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：打桩客户端返回固定 GREEN 结构（使状态机在无凭证下可验证）。 */
class StubGeminiClientTest {

    @Test
    void returnsFixedGreenStructuredResult() {
        GeminiTriageResult r = new StubGeminiClient().analyze("咳嗽", List.of("k1"));
        assertThat(r.dangerLevel()).isEqualTo("GREEN");
        assertThat(r.advice()).isNotBlank();
        assertThat(r.disclaimer()).isNotBlank();
        assertThat(r.raw()).containsEntry("stub", true);
    }
}
