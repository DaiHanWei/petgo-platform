package com.tailtopia.topic.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** L0：{@link TopicNameRules#normalize} 幂等、与默认 Locale 无关（V1.4.2 social-topic · Story 4.1 · AC3）。 */
class TopicNameRulesTest {

    @Test
    void normalizeIsIdempotent() {
        for (String raw : new String[] {"KucingLucu", "Café", "猫咪日常", "MyPet_2024"}) {
            String once = TopicNameRules.normalize(raw);
            assertThat(TopicNameRules.normalize(once)).isEqualTo(once);
        }
        assertThat(TopicNameRules.normalize("Café")).isEqualTo("café");
    }

    @Test
    void normalizeUsesLocaleRootEvenUnderTurkishDefault() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(TopicNameRules.normalize("ISTANBUL")).isEqualTo("istanbul");
            assertThat(TopicNameRules.effectiveDistinct("#ISTANBUL"))
                    .extracting(TopicToken::norm).containsExactly("istanbul");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void parseReturnsEveryOccurrenceAndMarksOverflowNotEffective() {
        var tokens = TopicNameRules.parse("#aa #AA #bb #cc #dd #ee #ff #FF");
        assertThat(tokens).hasSize(8);
        assertThat(tokens).filteredOn(TopicToken::effective).hasSize(6);
        assertThat(tokens).filteredOn(t -> !t.effective()).extracting(TopicToken::norm).containsOnly("ff");
        assertThat(TopicNameRules.effectiveDistinct("#aa #AA #bb #cc #dd #ee #ff"))
                .extracting(TopicToken::display).containsExactly("aa", "bb", "cc", "dd", "ee");
    }

    @Test
    void nullOrEmptyTextHasNoTopics() {
        assertThat(TopicNameRules.parse(null)).isEmpty();
        assertThat(TopicNameRules.parse("")).isEmpty();
        assertThat(TopicNameRules.effectiveDistinct(null)).isEmpty();
    }
}
