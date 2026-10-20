package com.tailtopia.notify.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.notify.preference.PushCategory;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0：V1.4.0 ④ Story 1.1 · AC5 —— 未知分类 code 在反序列化阶段失败（→ HttpMessageNotReadableException → 400）。
 */
class UpdatePushSettingsRequestJsonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void knownCodeParses() {
        UpdatePushSettingsRequest r = json.readValue("{\"categories\":{\"LIKE\":false}}",
                UpdatePushSettingsRequest.class);
        assertThat(r.categories()).containsEntry(PushCategory.LIKE, false).hasSize(1);
    }

    @Test
    void unknownCodeFails() {
        assertThatThrownBy(() -> json.readValue("{\"categories\":{\"FOO\":false}}", UpdatePushSettingsRequest.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void lowercaseCodeFails() {
        assertThatThrownBy(() -> json.readValue("{\"categories\":{\"like\":false}}", UpdatePushSettingsRequest.class))
                .isInstanceOf(JacksonException.class);
    }
}
