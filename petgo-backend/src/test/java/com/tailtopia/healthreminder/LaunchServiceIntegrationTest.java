package com.tailtopia.healthreminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.healthreminder.service.LaunchService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1（V1.5.0 Story 2.2 · AC4）：开关关闭什么都不做；打开后首跑写上线日（只写一次）。 */
class LaunchServiceIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LaunchService launch;

    @AfterEach
    void reset() {
        jdbc.update("UPDATE health_reminder_settings SET enabled = FALSE, launch_date = NULL WHERE id = 1");
    }

    @Test
    void disabledDoesNothingEnabledWritesLaunchDateOnce() {
        jdbc.update("UPDATE health_reminder_settings SET enabled = FALSE, launch_date = NULL WHERE id = 1");
        assertThat(launch.onRun(LocalDate.parse("2030-01-01")).enabled()).isFalse();
        jdbc.update("UPDATE health_reminder_settings SET enabled = TRUE, switched_on_at = now() WHERE id = 1");
        assertThat(launch.onRun(LocalDate.parse("2030-01-01")).firstRun()).isTrue();
        assertThat(launch.onRun(LocalDate.parse("2030-01-02")).firstRun()).isFalse();
        assertThat(jdbc.queryForObject("SELECT launch_date::text FROM health_reminder_settings WHERE id = 1",
                String.class)).isEqualTo("2030-01-01");
    }
}
