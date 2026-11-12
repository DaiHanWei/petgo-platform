package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** L0：Story 4.1 / 4.3 埋点库迁移的文本约束（授权白名单写法、无外键、视图、请求表不授权）。 */
class RollupMigrationTextTest {

    private static final String PATH = "db/analytics-migration/V20261113_1300__rollup_framework_activity_install.sql";

    private static final String PATH_43 = "db/analytics-migration/V20261113_1320__cohort_retention_and_install_profile.sql";

    private static String sql() throws IOException {
        return read(PATH);
    }

    private static String read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
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

    /** Story 4.3：组表与月表的列、约束、空值注释与授权。 */
    @Test
    void cohortRetentionAndInstallProfile() throws IOException {
        String s = read(PATH_43);
        assertThat(s).contains("CREATE TABLE agg_cohort_retention", "CREATE TABLE agg_monthly_install_profile");
        assertThat(s).contains("PRIMARY KEY (cohort_type, cohort_date)",
                "CHECK (cohort_type IN ('INSTALL', 'ONBOARDING'))",
                "CHECK (stat_month = date_trunc('month', stat_month)::date)");
        for (String c : new String[] {"d1_retained", "d3_retained", "d7_retained", "w1_retained"}) {
            assertThat(s).containsPattern("COMMENT ON COLUMN agg_cohort_retention\\." + c + " IS '[^']*空 = 未满");
        }
        for (String t : new String[] {"agg_cohort_retention", "agg_monthly_install_profile"}) {
            assertThat(s).contains("format('GRANT SELECT ON %I TO %I', '" + t + "', '${readerRole}')");
        }
        assertThat(s).contains("rolname = '${readerRole}'").doesNotContain("TO analytics_reader");
        assertThat(s).doesNotContainIgnoringCase("REFERENCES");
        // 汇总表不含可识别个人的列
        assertThat(s).doesNotContain("user_id", "anonymous_id", "person_key");
    }
}
