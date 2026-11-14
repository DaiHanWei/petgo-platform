package com.tailtopia.notify.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** L0：V1.5.0 地基 Story 0.1 · 召回日保守判定与纪念日占位。 */
class RetentionPushCalendarTest {

    private static final LocalDate REG = LocalDate.of(2026, 11, 1);

    @Test
    void recallWindowCoversTwoDaysOfEachNode() {
        // D1 窗口 = 注册后第 1、2 天；D3 = 第 3、4 天；D7 = 第 7、8 天
        for (int age : new int[] {1, 2, 3, 4, 7, 8}) {
            assertThat(RetentionPushCalendar.inRecallWindow(REG, REG.plusDays(age))).as("第 %d 天", age).isTrue();
        }
        for (int age : new int[] {0, 5, 6, 9, 10, 30}) {
            assertThat(RetentionPushCalendar.inRecallWindow(REG, REG.plusDays(age))).as("第 %d 天", age).isFalse();
        }
        assertThat(RetentionPushCalendar.inRecallWindow(REG, REG.minusDays(1))).as("注册日之前").isFalse();
        assertThat(RetentionPushCalendar.inRecallWindow(null, REG)).isFalse();
    }

    @Test
    void emptyInputNeverQueries() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        RetentionPushCalendar cal = new RetentionPushCalendar(jdbc);
        assertThat(cal.recallNodeUsers(List.of(), REG)).isEmpty();
        assertThat(cal.recallNodeUsers(null, REG)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void anniversaryIsPlaceholderEmptyUntilPushRecallImplementsIt() {
        RetentionPushCalendar cal = new RetentionPushCalendar(mock(NamedParameterJdbcTemplate.class));
        assertThat(cal.anniversaryPushPets(List.of(1L, 2L), REG)).isEmpty();
    }
}
