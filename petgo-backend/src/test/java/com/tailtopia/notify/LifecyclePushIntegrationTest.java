package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.PetStatus;
import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.lifecycle.LifecyclePushJob;
import com.tailtopia.notify.lifecycle.LifecyclePushProperties;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：生命周期推送分层（V1.5.0 onboarding-recall Story 6.1 · AC2 ~ AC11）—— 需 Docker postgres + redis，
 * 云端不跑，<b>L1 待本地验收</b>。真库造五类用户 + 新手标记 + {@code user_active_days} → 跑一次日扫 →
 * 断言 marks（{@code <层>_<变体>}）/ 通知行渲染文案 / targetRef。
 */
class LifecyclePushIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LifecyclePushJob job;
    @Autowired
    private LifecyclePushProperties props;
    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    @MockitoBean
    private NotificationPusher pusher;

    private boolean dropped;
    private boolean wasEnabled;
    private final List<Long> extraPets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
        wasEnabled = props.isEnabled();
        props.setEnabled(true);
    }

    @AfterEach
    void restore() {
        props.setEnabled(wasEnabled);
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    private User registeredDaysAgo(PetStatus status, int days) {
        User u = newUser(status);
        Instant created = LocalDate.now(ScheduleWindow.WIB).minusDays(days).atTime(12, 0)
                .atZone(ScheduleWindow.WIB).toInstant();
        // 6-2 版本门槛：只推 App ≥ 1.5.0
        jdbc.update("UPDATE users SET created_at = ?, last_app_version = '1.5.0' WHERE id = ?",
                java.sql.Timestamp.from(created), u.getId());
        return u;
    }

    private void pet(User owner, String name, String breed) {
        long id = MultiPetDbFixture.insertPet(jdbc, owner.getId(), name, "CAT", Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("UPDATE pet_profiles SET breed = ? WHERE id = ?", breed, id);
        extraPets.add(id);
    }

    private void mark(User u, String... keys) {
        for (String k : keys) {
            jdbc.update("INSERT INTO user_newbie_marks (user_id, task_key) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    u.getId(), k);
        }
    }

    private void published(User u, int n) {
        jdbc.update("UPDATE users SET published_count = ? WHERE id = ?", n, u.getId());
    }

    private void awaitAsyncIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        ThreadPoolExecutor pool = asyncExecutor.getThreadPoolExecutor();
        Thread.sleep(50);
        while (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("业务异步池 15s 内未排空");
            }
            Thread.sleep(20);
        }
    }

    private String markVariant(User u, String kind) {
        List<String> v = jdbc.queryForList("SELECT variant FROM lifecycle_push_marks WHERE user_id = ? AND push_kind = ?",
                String.class, u.getId(), kind);
        return v.isEmpty() ? null : v.get(0);
    }

    private Map<String, Object> notification(User u) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT type, title, body, target_ref FROM notifications WHERE recipient_user_id = ?", u.getId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void fiveLayersAcrossNodes() throws Exception {
        User planning = registeredDaysAgo(PetStatus.PLANNING, 1);          // A · D1 DISCOVER
        User squad = registeredDaysAgo(PetStatus.HAS_PET, 1);              // D · D1 SQUAD（Persia）
        pet(squad, "Mochi", "Persia");
        published(squad, 1);
        mark(squad, "FIRST_DIARY");
        User activeE = registeredDaysAgo(PetStatus.HAS_PET, 3);            // E · D3 不推
        pet(activeE, "Kopi", null);
        published(activeE, 1);
        mark(activeE, "FIRST_FOLLOW");
        User cameToday = registeredDaysAgo(PetStatus.HAS_PET, 3);          // C · D3 当天来过 → SKIPPED_ACTIVE
        pet(cameToday, "Bobo", null);
        jdbc.update("INSERT INTO user_active_days (user_id, active_date) VALUES (?, ?)", cameToday.getId(),
                LocalDate.now(ScheduleWindow.WIB));
        User graduate = registeredDaysAgo(PetStatus.HAS_PET, 7);           // D7 GRADUATED
        pet(graduate, "Lulu", null);
        mark(graduate, "FIRST_DIARY", "FIRST_LIKE_RECEIVED", "FIRST_FOLLOW", "FIRST_LIKE_GIVEN", "FIRST_COMMENT",
                "FIRST_REPLY");
        User fourOfSeven = registeredDaysAgo(PetStatus.HAS_PET, 7);        // D7 TASKS_LEFT（4/7 → 3）
        pet(fourOfSeven, "Momo", null);
        mark(fourOfSeven, "FIRST_DIARY", "FIRST_FOLLOW", "FIRST_REPLY");

        job.runDailyScan();
        awaitAsyncIdle();

        assertThat(markVariant(planning, "LIFECYCLE_D1")).isEqualTo("A_DISCOVER");
        assertThat(notification(planning)).containsEntry("target_ref", "DISCOVER");
        assertThat((String) notification(planning).get("title")).doesNotContain("Mochi");

        assertThat(markVariant(squad, "LIFECYCLE_D1")).isEqualTo("D_DISCOVER");
        assertThat(notification(squad)).containsEntry("target_ref", "DISCOVER");
        assertThat((String) notification(squad).get("title")).contains("Persia");

        assertThat(markVariant(activeE, "LIFECYCLE_D3")).isNull();
        assertThat(notification(activeE)).isNull();

        assertThat(markVariant(cameToday, "LIFECYCLE_D3")).isEqualTo("C_SKIPPED_ACTIVE");
        assertThat(notification(cameToday)).isNull();

        assertThat(markVariant(graduate, "LIFECYCLE_D7")).isEqualTo("C_GRADUATED");
        assertThat(notification(graduate)).containsEntry("target_ref", "GRADUATED");

        assertThat(markVariant(fourOfSeven, "LIFECYCLE_D7")).isEqualTo("C_NEWBIE_TASKS");
        assertThat(notification(fourOfSeven)).containsEntry("target_ref", "NEWBIE_TASKS");
        assertThat((String) notification(fourOfSeven).get("body")).contains("Tinggal 3");
    }
}
