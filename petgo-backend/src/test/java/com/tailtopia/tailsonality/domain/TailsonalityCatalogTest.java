package com.tailtopia.tailsonality.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.PetType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** V1.3.2 Story 2.1 · AC1：题库常量逐条对齐内容设计 §6.1–§6.5。 */
class TailsonalityCatalogTest {

    /** 内容设计 §6.2 / §6.3 / §6.4 / §6.5 现行权重（三套 54 题逐条核对后全部为此值）。 */
    private static final List<Integer> DOC_WEIGHTS = List.of(2, 1, -1, -2);

    @ParameterizedTest
    @EnumSource(TailsonalityQuestionSet.class)
    void eachSetHasExactly18QuestionsWith4WeightsMatchingTheContentDesign(TailsonalityQuestionSet set) {
        var w = TailsonalityCatalog.WEIGHTS.get(set);
        assertThat(w).hasSize(18);
        assertThat(w.keySet()).containsExactlyElementsOf(TailsonalityCatalog.QUESTION_IDS);
        for (String q : TailsonalityCatalog.QUESTION_IDS) {
            assertThat(w.get(q)).as(set + "/" + q).hasSize(4).isEqualTo(DOC_WEIGHTS);
        }
    }

    @Test
    void setsAreListedSeparatelyNotOneSharedInstance() {
        // 以后某题改权重时必须能只改一套：三套的题目表不是同一个对象。
        assertThat(TailsonalityCatalog.WEIGHTS.get(TailsonalityQuestionSet.CAT))
                .isNotSameAs(TailsonalityCatalog.WEIGHTS.get(TailsonalityQuestionSet.DOG))
                .isNotSameAs(TailsonalityCatalog.WEIGHTS.get(TailsonalityQuestionSet.GENERAL));
    }

    @Test
    void questionIdsAreOrdered18() {
        assertThat(TailsonalityCatalog.QUESTION_IDS).containsExactly(
                "Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8", "Q9", "Q10",
                "Q11", "Q12", "Q13", "Q14", "Q15", "P1", "P2", "P3");
    }

    @Test
    void axesAreDisjointAndCoverAll18() {
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (var e : TailsonalityCatalog.AXIS_QUESTIONS.entrySet()) {
            total += e.getValue().size();
            seen.addAll(e.getValue());
        }
        assertThat(total).isEqualTo(18);
        assertThat(seen).isEqualTo(new HashSet<>(TailsonalityCatalog.QUESTION_IDS));
        assertThat(TailsonalityCatalog.AXIS_QUESTIONS.get(TailsonalityAxis.EI)).containsExactly("Q1", "Q2", "Q3", "P1");
        assertThat(TailsonalityCatalog.AXIS_QUESTIONS.get(TailsonalityAxis.NS)).containsExactly("Q4", "Q5", "Q6", "Q7");
        assertThat(TailsonalityCatalog.AXIS_QUESTIONS.get(TailsonalityAxis.TF)).containsExactly("Q8", "Q9", "Q10", "P2");
        assertThat(TailsonalityCatalog.AXIS_QUESTIONS.get(TailsonalityAxis.JP)).containsExactly("Q11", "Q12", "Q13");
        assertThat(TailsonalityCatalog.AXIS_QUESTIONS.get(TailsonalityAxis.ENERGY)).containsExactly("Q14", "Q15", "P3");
    }

    @Test
    void anchorsBelongToTheirAxis() {
        for (TailsonalityAxis axis : TailsonalityAxis.values()) {
            assertThat(TailsonalityCatalog.AXIS_QUESTIONS.get(axis)).contains(TailsonalityCatalog.ANCHOR.get(axis));
            assertThat(TailsonalityCatalog.AXIS_QUESTIONS.get(axis).get(0)).isEqualTo(TailsonalityCatalog.ANCHOR.get(axis));
        }
        assertThat(TailsonalityCatalog.ANCHOR.values()).containsExactly("Q1", "Q4", "Q8", "Q11", "Q14");
    }

    @Test
    void sixteenDistinctTypeCodes() {
        assertThat(TailsonalityCatalog.TYPE_CODES).hasSize(16).doesNotHaveDuplicates()
                .allMatch(c -> c.matches("^[EI][NS][TF][JP]$"));
    }

    @Test
    void emotionAxisIsTheOnlyReversedAxis() {
        assertThat(TailsonalityAxis.TF.positiveLetter()).isEqualTo('F');
        assertThat(TailsonalityAxis.TF.negativeLetter()).isEqualTo('T');
        assertThat(TailsonalityAxis.EI.positiveLetter()).isEqualTo('E');
        assertThat(TailsonalityAxis.NS.positiveLetter()).isEqualTo('N');
        assertThat(TailsonalityAxis.JP.positiveLetter()).isEqualTo('J');
        assertThat(TailsonalityAxis.ENERGY.positiveLetter()).isEqualTo('H');
    }

    @Test
    void petTypeMapsToQuestionSet() {
        assertThat(TailsonalityCatalog.forPetType(PetType.CAT)).isEqualTo(TailsonalityQuestionSet.CAT);
        assertThat(TailsonalityCatalog.forPetType(PetType.DOG)).isEqualTo(TailsonalityQuestionSet.DOG);
        assertThat(TailsonalityCatalog.forPetType(PetType.OTHER)).isEqualTo(TailsonalityQuestionSet.GENERAL);
    }
}
