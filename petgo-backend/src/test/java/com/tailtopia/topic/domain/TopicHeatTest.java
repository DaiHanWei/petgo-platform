package com.tailtopia.topic.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** L0 · V1.4.2 social-topic Story 4.2 · AC7.7：热度公式唯一一份（{@link TopicHeat}）。 */
class TopicHeatTest {

    @Test
    void scoreMatchesPrdFormula() {
        // 3 帖 × 1 + 25 人天 × 0.1 + 4 赞 × 1 + 2 评 × 1 = 11.5
        assertThat(TopicHeat.score(3, 25, 4, 2)).isEqualByComparingTo("11.5");
    }

    @Test
    void allZeroIsZero() {
        assertThat(TopicHeat.score(0, 0, 0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void weightsAreAsSpecified() {
        // 防手滑把 0.1 改成 1
        assertThat(TopicHeat.VIEW_WEIGHT).isEqualByComparingTo("0.1");
        assertThat(TopicHeat.POST_WEIGHT).isEqualByComparingTo("1");
        assertThat(TopicHeat.INTERACTION_WEIGHT).isEqualByComparingTo("1");
        assertThat(TopicHeat.WINDOW_DAYS).isEqualTo(7);
    }

    @Test
    void scoreKeepsOneDecimal() {
        assertThat(TopicHeat.score(0, 1, 0, 0)).isEqualByComparingTo("0.1");
        assertThat(TopicHeat.score(0, 1, 0, 0).scale()).isEqualTo(1);
    }
}
