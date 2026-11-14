package com.tailtopia.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.lifecycle.RetentionPushCalendar;
import com.tailtopia.notify.service.AppVersionGate;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：V1.5.0 地基 Story 0.1 —— 请求头写入 {@code users.last_app_version}、版本门槛批量判定、召回日 SQL。
 * 迁移本身（{@code V20261114_1000} / {@code V20261114_1001}）随上下文启动由 Flyway 应用，ddl-auto=validate 一并校验。
 */
class V150FoundationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AppVersionGate gate;
    @Autowired
    private RetentionPushCalendar calendar;
    @Autowired
    private JdbcTemplate jdbc;

    private String storedVersion(long userId) {
        return jdbc.queryForObject("SELECT last_app_version FROM users WHERE id = ?", String.class, userId);
    }

    @Test
    void requestHeaderIsStoredAndGateReadsIt() throws Exception {
        User newer = newUser();
        User older = newUser();
        User silent = newUser();

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, userBearer(newer.getId()))
                .header("X-App-Version", "1.5.0-stag"));
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, userBearer(older.getId()))
                .header("X-App-Version", "1.4.2"));
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, userBearer(silent.getId())));

        assertThat(storedVersion(newer.getId())).isEqualTo("1.5.0-stag");
        assertThat(storedVersion(older.getId())).isEqualTo("1.4.2");
        assertThat(storedVersion(silent.getId())).as("不带头不写").isNull();
        assertThat(gate.usersOnV150OrLater(List.of(newer.getId(), older.getId(), silent.getId(), -1L)))
                .containsExactly(newer.getId());

        // 升级后再来一次：覆盖为新值
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, userBearer(older.getId()))
                .header("X-App-Version", "1.5.1"));
        assertThat(gate.reportedV150OrLater(older.getId())).isTrue();
    }

    @Test
    void recallNodeUsersByWibRegistrationDate() {
        User d1 = newUser();
        User d5 = newUser();
        LocalDate today = LocalDate.now(ScheduleWindow.WIB);
        // 注册时间按 WIB 当天中午回拨，避免跨日边界
        jdbc.update("UPDATE users SET created_at = ((?::date + time '12:00') AT TIME ZONE 'Asia/Jakarta') WHERE id = ?",
                today.minusDays(1), d1.getId());
        jdbc.update("UPDATE users SET created_at = ((?::date + time '12:00') AT TIME ZONE 'Asia/Jakarta') WHERE id = ?",
                today.minusDays(5), d5.getId());

        assertThat(calendar.recallNodeUsers(List.of(d1.getId(), d5.getId()), today)).containsExactly(d1.getId());
    }
}
