package com.tailtopia.shared.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.PetSpecies;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 pet-species Story 1.3 · AC4 —— AI 分诊物种行按细分类型输出中文。 */
class GeminiSpeciesLabelTest {

    @Test
    void labels() {
        assertThat(GeminiDeveloperApiClient.speciesLabel("RABBIT")).isEqualTo("兔");
        assertThat(GeminiDeveloperApiClient.speciesLabel("CIVET")).isEqualTo("麝香猫");
        assertThat(GeminiDeveloperApiClient.speciesLabel("OTHER")).isEqualTo("其他伴侣动物");
        assertThat(GeminiDeveloperApiClient.speciesLabel("CAT")).isEqualTo("猫");
        assertThat(GeminiDeveloperApiClient.speciesLabel("DRAGON")).isEqualTo("其他伴侣动物");
        assertThat(GeminiDeveloperApiClient.speciesLabel(null)).isNull();
        for (PetSpecies s : PetSpecies.values()) {
            assertThat(GeminiDeveloperApiClient.speciesLabel(s.name())).isNotBlank();
        }
    }

    @Test
    void nullSpeciesOmitsLine() {
        GeminiDeveloperApiClient client = new GeminiDeveloperApiClient(new GeminiProperties());
        String prompt = client.buildPrompt("x", new TriagePetContext(null, 11, null));
        assertThat(prompt).doesNotContain("物种");
    }

    @Test
    void rabbitLineInPrompt() {
        GeminiDeveloperApiClient client = new GeminiDeveloperApiClient(new GeminiProperties());
        String prompt = client.buildPrompt("x", new TriagePetContext("RABBIT", 11, null));
        assertThat(prompt).contains("物种：兔");
    }
}
