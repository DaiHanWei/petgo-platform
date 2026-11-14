package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.lifecycle.RetentionPushCalendar;
import com.tailtopia.notify.schedule.ScheduledPushJob;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：陪伴纪念日 19:00 WIB 日扫（V1.5.0 onboarding-recall Story 5.2 · AC3 ~ AC6）—— 需 Docker postgres + redis，
 * 云端不跑，<b>L1 待本地验收</b>。真库：同主人两宠同天满 30 天 → 通知中心两条（存渲染结果）、推送一条（建档早的那只）、
 * marks 两行；只读宠物不推不写；{@code anniversaryPushPets} 与实际推送集合一致。
 */
class AnniversaryPushIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ScheduledPushJob job;
    @Autowired
    private RetentionPushCalendar calendar;
    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    @MockitoBean
    private NotificationPusher pusher;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    private long pet(User owner, String name, Instant createdAt) {
        long id = MultiPetDbFixture.insertPet(jdbc, owner.getId(), name, "CAT", createdAt);
        extraPets.add(id);
        return id;
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

    private Set<Long> pushedPetIds() {
        return org.mockito.Mockito.mockingDetails(pusher).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("pushToUser"))
                .filter(i -> "COMPANION_ANNIVERSARY".equals(i.getArgument(3)))
                .map(i -> Long.valueOf((String) i.getArgument(5)))
                .collect(Collectors.toSet());
    }

    @Test
    void twoPetsSameDayOnePushTwoNotificationsReadOnlyExcluded() throws Exception {
        LocalDate today = LocalDate.now(ScheduleWindow.WIB);
        Instant thirtyDaysAgoNoon = today.minusDays(30).atTime(12, 0).atZone(ScheduleWindow.WIB).toInstant();
        User owner = newUser();
        long kopi = pet(owner, "Kopi", thirtyDaysAgoNoon);
        long mochi = pet(owner, "Mochi", thirtyDaysAgoNoon.plusSeconds(600));
        User other = newUser();
        long ro = pet(other, "Ro", thirtyDaysAgoNoon);
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", ro);

        job.runAnniversaryScan();
        awaitAsyncIdle();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'COMPANION_ANNIVERSARY'", Long.class, owner.getId())).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT title FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'COMPANION_ANNIVERSARY'", String.class, owner.getId()))
                .allMatch(t -> t.startsWith("🎉 Hari ke-30 bareng "));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduled_push_marks WHERE pet_profile_id IN (?, ?) "
                + "AND push_kind = 'COMPANION_ANNIVERSARY'", Long.class, kopi, mochi)).isEqualTo(2);
        assertThat(pushedPetIds()).containsExactly(kopi);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?",
                Long.class, other.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduled_push_marks WHERE pet_profile_id = ?",
                Long.class, ro)).isZero();

        assertThat(calendar.anniversaryPushPets(List.of(kopi, mochi, ro), today)).containsExactly(kopi);
    }
}
