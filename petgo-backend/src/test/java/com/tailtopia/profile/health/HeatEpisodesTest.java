package com.tailtopia.profile.health;

import static com.tailtopia.profile.health.HealthFixtures.d;
import static com.tailtopia.profile.health.HealthFixtures.rec;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.5.0 Story 1.2 · AC4）：PRD FR-171 / AC35 周期分组。 */
class HeatEpisodesTest {

    private static HealthRecordSnapshot heat(String date) {
        return rec(HealthRecordType.MENSTRUATION, date, null);
    }

    @Test
    void ac35CatOneTwoFourMarchIsOneEpisode() {
        var e = HeatEpisodes.of(List.of(heat("2027-03-01"), heat("2027-03-02"), heat("2027-03-04")), PetType.CAT);
        assertThat(e.episodes()).hasSize(1);
        var e2 = HeatEpisodes.of(List.of(heat("2027-03-01"), heat("2027-03-11")), PetType.CAT);
        assertThat(e2.episodes()).hasSize(2); // 11 Mar 起新周期
    }

    @Test
    void ac35CatDailyOneToSevenThenFifteenthIsNewEpisodeNoChaining() {
        List<HealthRecordSnapshot> list = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            list.add(heat("2027-03-0" + day));
        }
        list.add(heat("2027-03-15"));
        var e = HeatEpisodes.of(list, PetType.CAT);
        assertThat(e.episodes()).hasSize(2);
        assertThat(e.episodes().get(1).firstDay()).isEqualTo(d("2027-03-15"));
    }

    @Test
    void ac35DogOneThreeSixJanIsOneEpisodeFirstIsFirstJan() {
        HealthRecordSnapshot first = heat("2027-01-01");
        HealthRecordSnapshot later = heat("2027-01-06");
        var e = HeatEpisodes.of(List.of(later, heat("2027-01-03"), first), PetType.DOG);
        assertThat(e.episodes()).hasSize(1);
        assertThat(e.isFirst(first.id())).isTrue();
        assertThat(e.isFirst(later.id())).isFalse();
        assertThat(e.firstDaysUpTo(d("2027-12-31"))).containsExactly(d("2027-01-01"));
    }

    @Test
    void dogWindowIs21DaysAndOtherSpeciesToo() {
        assertThat(HeatEpisodes.of(List.of(heat("2027-01-01"), heat("2027-01-21")), PetType.DOG).episodes())
                .hasSize(1);
        assertThat(HeatEpisodes.of(List.of(heat("2027-01-01"), heat("2027-01-22")), PetType.DOG).episodes())
                .hasSize(2);
        assertThat(HeatEpisodes.windowDays(PetType.OTHER)).isEqualTo(21);
    }
}
