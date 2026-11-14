package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.PetStatus;
import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.lifecycle.LifecyclePushJob;
import com.tailtopia.notify.lifecycle.LifecyclePushProperties;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
 * L1：生命周期推送放量护栏（V1.5.0 onboarding-recall Story 6.2 · AC1 / AC3 / AC4）—— 需 Docker postgres + redis，
 * 云端不跑，<b>L1 待本地验收</b>：召回关 → 30 天没来的老用户无推送；注册满 10 天无 D7；旧版用户无 mark 无通知。
 */
class LifecyclePushRolloutIntegrationTest extends ApiIntegrationTest {

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

    private boolean wasEnabled;
    private boolean wasWinback;

    @BeforeEach
    void setUp() {
        wasEnabled = props.isEnabled();
        wasWinback = props.isWinbackEnabled();
        props.setEnabled(true);
        props.setWinbackEnabled(false);
    }

    @AfterEach
    void restore() {
        props.setEnabled(wasEnabled);
        props.setWinbackEnabled(wasWinback);
    }

    private User user(int registeredDaysAgo, String appVersion, Integer lastActiveDaysAgo) {
        User u = newUser(PetStatus.PLANNING);
        LocalDate today = LocalDate.now(ScheduleWindow.WIB);
        Instant created = today.minusDays(registeredDaysAgo).atTime(12, 0).atZone(ScheduleWindow.WIB).toInstant();
        Instant lastActive = lastActiveDaysAgo == null ? null
                : today.minusDays(lastActiveDaysAgo).atTime(12, 0).atZone(ScheduleWindow.WIB).toInstant();
        jdbc.update("UPDATE users SET created_at = ?, last_app_version = ?, last_active_at = ? WHERE id = ?",
                Timestamp.from(created), appVersion, lastActive == null ? null : Timestamp.from(lastActive), u.getId());
        return u;
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

    private long marks(User u) {
        return jdbc.queryForObject("SELECT count(*) FROM lifecycle_push_marks WHERE user_id = ?", Long.class, u.getId());
    }

    private long notifications(User u) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", Long.class,
                u.getId());
    }

    @Test
    void rolloutGuards() throws Exception {
        User lapsed = user(60, "1.5.0", 30);   // 召回关 → 不推
        User tenDays = user(10, "1.5.0", 10);  // 上线当天满 10 天 → 无 D7（窗口 2 天）
        User oldApp = user(1, "1.4.2", null);  // 旧版 → 无 mark 无通知
        User neverReported = user(1, null, null);
        User fresh = user(1, "1.5.0", null);   // 对照：新版 D1 照发

        job.runDailyScan();
        awaitAsyncIdle();

        assertThat(marks(lapsed)).isZero();
        assertThat(notifications(lapsed)).isZero();
        assertThat(marks(tenDays)).isZero();
        assertThat(marks(oldApp)).isZero();
        assertThat(notifications(oldApp)).isZero();
        assertThat(marks(neverReported)).isZero();
        assertThat(marks(fresh)).isEqualTo(1);
        assertThat(notifications(fresh)).isEqualTo(1);
    }
}
