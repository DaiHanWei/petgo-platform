package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** L0：Story 4.1 埋点库迁移的文本约束（授权白名单写法、无外键、视图、请求表不授权）。 */
class RollupMigrationTextTest {

    private static final String PATH = "db/analytics-migration/V20261113_1300__rollup_framework_activity_install.sql";

    private static String sql() throws IOException {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void createsTablesAndView() throws IOException {
        String s = sql();
        assertThat(s).contains("CREATE TABLE agg_progress", "CREATE TABLE agg_daily_activity",
                "CREATE TABLE agg_daily_install", "CREATE VIEW v_events_person", "CREATE TABLE rollup_rerun_requests");
        assertThat(s).contains("PRIMARY KEY (stat_date, platform)");
        assertThat(s).contains("COMMENT ON COLUMN agg_daily_activity.stat_date IS 'WIB 日期");
        assertThat(s).doesNotContainIgnoringCase("REFERENCES");
    }

    @Test
    void grantsViaReaderRolePlaceholderOnly() throws IOException {
        String s = sql();
        assertThat(s).contains("rolname = '${readerRole}'");
        for (String t : new String[] {"agg_progress", "agg_daily_activity", "agg_daily_install", "v_events_person"}) {
            assertThat(s).contains("format('GRANT SELECT ON %I TO %I', '" + t + "', '${readerRole}')");
        }
        assertThat(s).doesNotContain("'rollup_rerun_requests', '${readerRole}'");
        assertThat(s).doesNotContain("TO analytics_reader");
    }

    @Test
    void viewImplementsD10() throws IOException {
        String s = sql();
        assertThat(s).contains("l.linked_at <= d.event_time", "ORDER BY l.linked_at DESC LIMIT 1",
                "l.linked_at > d.event_time", "ORDER BY l.linked_at ASC LIMIT 1", "'a:' || d.anonymous_id");
    }
}
