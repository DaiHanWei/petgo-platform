package com.tailtopia.notify.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.UserLifecycleSnapshot;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.port.LifecycleContextPort.DefaultPet;
import com.tailtopia.notify.port.LifecycleContextPort.LifecycleContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * L0：召回日保守判定 ⊇ 实际推送（V1.5.0 onboarding-recall Story 6.2 · AC5）—— 对同一批用户与日期，
 * {@link RetentionPushCalendar#recallNodeUsers}（方法体未改）的结果必须包含 6-1 计划器实际会推的 D1 / D3 / D7 用户：
 * 当天来过、D3 E 层、旧版只会让实际发送更少。纯数据驱动（固定种子随机造 400 人：注册 0~12 天、五层随机、随机当天来过）。
 */
class RetentionPushCalendarConsistencyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    @Test
    @DisplayName("recallNodeUsers ⊇ 计划器实际推送（非 SKIPPED_ACTIVE）的 D1 / D3 / D7 用户")
    @SuppressWarnings("unchecked")
    void conservativeSupersetOfActualPushes() {
        Random rnd = new Random(20261115L);
        List<UserLifecycleSnapshot> users = new ArrayList<>();
        Map<Long, DefaultPet> pets = new HashMap<>();
        Map<Long, Set<String>> marks = new HashMap<>();
        Set<Long> activeToday = new HashSet<>();
        List<Object[]> regRows = new ArrayList<>();
        String[] status = {null, "HAS_PET", "PLANNING", "ENTHUSIAST"};
        List<String> allMarks = LifecyclePushPlanner.NEWBIE_FIRST_SIX;
        for (long id = 1; id <= 400; id++) {
            LocalDate reg = TODAY.minusDays(rnd.nextInt(13));
            users.add(new UserLifecycleSnapshot(id, reg, null, rnd.nextInt(3), status[rnd.nextInt(status.length)]));
            regRows.add(new Object[] {id, reg});
            if (rnd.nextBoolean()) {
                pets.put(id, new DefaultPet("P" + id, rnd.nextBoolean() ? "Persia" : null, "CAT"));
            }
            Set<String> m = new HashSet<>();
            for (String k : allMarks) {
                if (rnd.nextInt(3) == 0) {
                    m.add(k);
                }
            }
            marks.put(id, m);
            if (rnd.nextInt(4) == 0) {
                activeToday.add(id);
            }
        }
        LifecycleContext ctx = new LifecycleContext(pets, marks, activeToday, Set.of());
        Set<Long> pushed = new LifecyclePushPlanner().plan(TODAY, users, ctx, new LifecyclePlanOptions(false, 7))
                .stream()
                .filter(p -> p.variant() != LifecycleVariant.SKIPPED_ACTIVE)
                .filter(p -> p.type() != NotificationType.LIFECYCLE_WINBACK)
                .map(LifecyclePlannedPush::userId)
                .collect(Collectors.toSet());
        assertThat(pushed).as("数据里应有实际推送").isNotEmpty();

        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(regRows);
        Set<Long> recallDays = new RetentionPushCalendar(jdbc)
                .recallNodeUsers(users.stream().map(UserLifecycleSnapshot::userId).toList(), TODAY);

        assertThat(recallDays).containsAll(pushed);
    }
}
