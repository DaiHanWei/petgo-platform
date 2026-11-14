package com.tailtopia.healthreminder.rules;

import static com.tailtopia.healthreminder.rules.RuleFixtures.d;
import static com.tailtopia.healthreminder.rules.RuleFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.health.PetHealthSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 2.2 · AC2）：PRD FR-164 体检到期算法，AC24 / AC25 / AC26 / AC30。 */
class CheckupDueRuleTest {

    @ParameterizedTest(name = "AC26 {0} 生 {1}，到访 {2} → {3}（senior={4}）")
    @CsvSource({
        "CAT, 2017-01-01, 2026-10-01, 2027-04-01, true",
        "CAT, 2017-01-01, 2026-04-01, 2027-01-01, true",
        "CAT, 2017-01-01, 2027-01-01, 2027-07-01, true",
        "CAT, 2017-01-01, 2025-01-01, 2026-01-01, false",
        "CAT, 2017-01-01, 2027-03-01, 2027-09-01, true",
        "DOG, 2020-01-01, 2026-05-01, 2027-01-01, true",
        "DOG, 2020-01-01, 2027-01-01, 2027-07-01, true",
        "DOG, 2020-01-01, 2025-01-01, 2026-01-01, false"})
    void ac26(PetType type, String birthday, String visit, String due, boolean senior) {
        var r = CheckupDueRule.due(pet(type, PetSex.FEMALE, birthday, null), d(visit), null);
        assertThat(r.dueDate()).isEqualTo(d(due));
        assertThat(r.senior()).isEqualTo(senior);
    }

    @Test
    void ac25TwoYearOldDogConsultPlusTwelve() {
        var dog = pet(PetType.DOG, PetSex.MALE, "2025-01-01", null);
        assertThat(CheckupDueRule.due(dog, d("2027-03-05"), null).dueDate()).isEqualTo(d("2028-03-05"));
    }

    @Test
    void ac24NoBirthdayTwelveMonthsAndNeverSenior() {
        var noBday = pet(PetType.CAT, PetSex.FEMALE, null, null);
        var r = CheckupDueRule.due(noBday, d("2026-05-01"), null);
        assertThat(r.dueDate()).isEqualTo(d("2027-05-01"));
        assertThat(r.senior()).isFalse();
    }

    @Test
    void ac30VisitMovesBaseAndSentCheckupCountsAsBase() {
        var dog = pet(PetType.DOG, PetSex.MALE, "2024-01-01", null);
        // 无到访：base = 建档日 2025-01-01（夹具）→ 2026-01-01
        assertThat(CheckupDueRule.due(dog, null, null).dueDate()).isEqualTo(d("2026-01-01"));
        assertThat(CheckupDueRule.due(dog, d("2027-06-01"), d("2026-06-10")).dueDate()).isEqualTo(d("2028-06-01"));
        // 已发体检晚于到访 → 用发送日
        assertThat(CheckupDueRule.due(dog, d("2026-01-01"), d("2026-06-10")).dueDate()).isEqualTo(d("2027-06-10"));
    }

    @Test
    void otherSpeciesNone() {
        PetHealthSnapshot other = pet(PetType.OTHER, PetSex.FEMALE, "2020-01-01", null);
        assertThat(CheckupDueRule.due(other, d("2026-01-01"), null)).isNull();
    }
}
