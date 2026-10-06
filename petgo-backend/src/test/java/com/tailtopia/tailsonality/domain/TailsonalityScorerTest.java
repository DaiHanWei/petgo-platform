package com.tailtopia.tailsonality.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * V1.3.2 Story 2.1 · AC2：计分验收用例（内容设计 §6.7 V-1 ~ V-5，三套各跑一遍）+ 五轴平局向量。
 *
 * <p>序号 → 权重：0=+2, 1=+1, 2=−1, 3=−2。
 */
class TailsonalityScorerTest {

    private static final List<String> EMOTION = List.of("Q8", "Q9", "Q10", "P2");

    private static Map<String, Integer> all(int idx) {
        Map<String, Integer> m = new LinkedHashMap<>();
        TailsonalityCatalog.QUESTION_IDS.forEach(q -> m.put(q, idx));
        return m;
    }

    private static String score(TailsonalityQuestionSet set, Map<String, Integer> a) {
        return TailsonalityScorer.score(set, a).full();
    }

    @ParameterizedTest
    @EnumSource(TailsonalityQuestionSet.class)
    void v1AllFirstOption(TailsonalityQuestionSet set) {
        assertThat(score(set, all(0))).isEqualTo("ENFJ-H");
    }

    @ParameterizedTest
    @EnumSource(TailsonalityQuestionSet.class)
    void v2AllLastOption(TailsonalityQuestionSet set) {
        assertThat(score(set, all(3))).isEqualTo("ISTP-L");
    }

    @ParameterizedTest
    @EnumSource(TailsonalityQuestionSet.class)
    void v3EmotionFirstOthersLast(TailsonalityQuestionSet set) {
        Map<String, Integer> a = all(3);
        EMOTION.forEach(q -> a.put(q, 0));
        assertThat(score(set, a)).isEqualTo("ISFP-L");
    }

    @ParameterizedTest
    @EnumSource(TailsonalityQuestionSet.class)
    void v4EmotionLastOthersFirst(TailsonalityQuestionSet set) {
        Map<String, Integer> a = all(0);
        EMOTION.forEach(q -> a.put(q, 3));
        assertThat(score(set, a)).isEqualTo("ENTJ-H");
    }

    @ParameterizedTest
    @EnumSource(TailsonalityQuestionSet.class)
    void v5DriveTieFallsBackToAnchor(TailsonalityQuestionSet set) {
        Map<String, Integer> a = all(0);
        a.put("Q11", 0);
        a.put("Q12", 2);
        a.put("Q13", 2);
        assertThat(TailsonalityScorer.score(set, a).letters().charAt(3)).isEqualTo('J');
    }

    // ===== 五轴平局向量（Dev Notes 表） =====

    private static char letterAt(Map<String, Integer> overrides, int pos) {
        Map<String, Integer> a = all(0);
        a.putAll(overrides);
        TailsonalityCode c = TailsonalityScorer.score(TailsonalityQuestionSet.CAT, a);
        return pos < 4 ? c.letters().charAt(pos) : c.energy().charAt(0);
    }

    @Test
    void tieSocialAnchorPositiveIsE() {
        assertThat(letterAt(Map.of("Q1", 1, "Q2", 2, "Q3", 0, "P1", 3), 0)).isEqualTo('E');
    }

    @Test
    void tieSocialAnchorNegativeIsI() {
        assertThat(letterAt(Map.of("Q1", 3, "Q2", 0, "Q3", 2, "P1", 1), 0)).isEqualTo('I');
    }

    @Test
    void tieExplorationAnchorNegativeIsS() {
        assertThat(letterAt(Map.of("Q4", 2, "Q5", 1, "Q6", 0, "Q7", 3), 1)).isEqualTo('S');
    }

    @Test
    void tieEmotionAnchorPositiveIsF_notT() {
        // 专抓「把 T 当正极」的实现。
        assertThat(letterAt(Map.of("Q8", 1, "Q9", 3, "Q10", 0, "P2", 2), 2)).isEqualTo('F');
    }

    @Test
    void tieDriveAnchorPositiveIsJ() {
        assertThat(letterAt(Map.of("Q11", 0, "Q12", 2, "Q13", 2), 3)).isEqualTo('J');
    }

    @Test
    void tieEnergyAnchorNegativeIsL() {
        assertThat(letterAt(Map.of("Q14", 2, "Q15", 0, "P3", 2), 4)).isEqualTo('L');
    }

    @Test
    void codeFormatting() {
        TailsonalityCode c = new TailsonalityCode("ENTJ", "H");
        assertThat(c.full()).isEqualTo("ENTJ-H");
        assertThat(c.letters()).isEqualTo("ENTJ");
        assertThat(c.energy()).isEqualTo("H");
    }
}
