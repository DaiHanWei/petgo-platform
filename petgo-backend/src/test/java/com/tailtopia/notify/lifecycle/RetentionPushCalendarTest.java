package com.tailtopia.notify.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.dto.PetProfileSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** L0：V1.5.0 地基 Story 0.1 · 召回日保守判定；V1.5.0 onboarding-recall Story 5.2 · 纪念日占位（已实现）。 */
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

    // ===== V1.5.0 onboarding-recall Story 5.2 · AC6 / AC7：纪念日占位已由会话 ③ 实现 =====

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private static PetProfileSnapshot pet(long id, long owner, Instant createdAt, String status, boolean ownerActive) {
        return new PetProfileSnapshot(id, owner, "Pet" + id, null, createdAt.atZone(ZoneOffset.UTC).toLocalDate(),
                createdAt.atZone(WIB).toLocalDate(), status, ownerActive, createdAt);
    }

    private static Instant wibNoonDaysAgo(int days, int extraSeconds) {
        return TODAY.minusDays(days).atTime(12, 0).atZone(WIB).toInstant().plusSeconds(extraSeconds);
    }

    @SuppressWarnings("unchecked")
    private static RetentionPushCalendar calendarReturning(List<PetProfileSnapshot> ownersPets) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(ownersPets);
        return new RetentionPushCalendar(jdbc);
    }

    @Test
    void anniversaryEmptyInputNeverQueries() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        RetentionPushCalendar cal = new RetentionPushCalendar(jdbc);
        assertThat(cal.anniversaryPushPets(List.of(), TODAY)).isEmpty();
        assertThat(cal.anniversaryPushPets(null, TODAY)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void anniversaryReadOnlyPetIsNotOccupied() {
        RetentionPushCalendar cal = calendarReturning(List.of(pet(1, 10, wibNoonDaysAgo(30, 0), "READ_ONLY", true)));
        assertThat(cal.anniversaryPushPets(List.of(1L), TODAY)).isEmpty();
    }

    @Test
    void anniversaryInactiveOwnerIsNotOccupied() {
        RetentionPushCalendar cal = calendarReturning(List.of(pet(1, 10, wibNoonDaysAgo(30, 0), "ACTIVE", false)));
        assertThat(cal.anniversaryPushPets(List.of(1L), TODAY)).isEmpty();
    }

    @Test
    void anniversarySameOwnerOnlyEarliestIsOccupied() {
        // 入参只问 Mochi（晚建档），但主人的另一只 Kopi（早建档、不在入参）同天也满节点 → Mochi 不算占用
        PetProfileSnapshot kopi = pet(1, 10, wibNoonDaysAgo(30, 0), "ACTIVE", true);
        PetProfileSnapshot mochi = pet(2, 10, wibNoonDaysAgo(30, 60), "ACTIVE", true);
        RetentionPushCalendar cal = calendarReturning(List.of(kopi, mochi));
        assertThat(cal.anniversaryPushPets(List.of(2L), TODAY)).isEmpty();
        assertThat(cal.anniversaryPushPets(List.of(1L, 2L), TODAY)).containsExactly(1L);
    }

    @Test
    void anniversaryUsesWibCreatedDate() {
        // UTC 10-15 20:00 建档 = WIB 10-16 03:00（WIB 00:00–07:00 建档）→ 11-15 按 WIB 算正好 30 天
        PetProfileSnapshot p = pet(1, 10, Instant.parse("2026-10-15T20:00:00Z"), "ACTIVE", true);
        RetentionPushCalendar cal = calendarReturning(List.of(p));
        assertThat(cal.anniversaryPushPets(List.of(1L), TODAY)).containsExactly(1L);
        assertThat(cal.anniversaryPushPets(List.of(1L), TODAY.minusDays(1))).isEmpty();
    }

    @Test
    void anniversaryMatchesNineteenOClockScanPushSet() {
        // 同一组数据：日扫实际 push=true 的宠物集合 == anniversaryPushPets 结果
        List<PetProfileSnapshot> all = List.of(
                pet(1, 10, wibNoonDaysAgo(30, 0), "ACTIVE", true),
                pet(2, 10, wibNoonDaysAgo(100, 0), "ACTIVE", true),
                pet(3, 20, wibNoonDaysAgo(365, 0), "ACTIVE", true),
                pet(4, 30, wibNoonDaysAgo(30, 0), "READ_ONLY", true),
                pet(5, 40, wibNoonDaysAgo(30, 0), "ACTIVE", false),
                pet(6, 50, wibNoonDaysAgo(31, 0), "ACTIVE", true));
        java.util.Set<Long> scanPushed = new com.tailtopia.notify.schedule.ScheduledPushPlanner()
                .planAnniversaries(TODAY, all, java.util.Set.of()).stream()
                .filter(com.tailtopia.notify.schedule.PlannedPush::push)
                .map(com.tailtopia.notify.schedule.PlannedPush::petProfileId)
                .collect(java.util.stream.Collectors.toSet());
        RetentionPushCalendar cal = calendarReturning(all);
        assertThat(cal.anniversaryPushPets(List.of(1L, 2L, 3L, 4L, 5L, 6L), TODAY))
                .isEqualTo(scanPushed).containsExactlyInAnyOrder(2L, 3L);
    }
}
