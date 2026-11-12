package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * L0：明细层迁移文本守卫（analytics-pipeline Story 1.6 AC1 / AC2；读文件，不连库）。
 *
 * <p>测试库没有只读角色，授权范围改为断言迁移文本：只授 {@code events_detail}，不授 {@code pipeline_progress}，不写死角色名。
 */
class EventsDetailMigrationTextTest {

    private static final Path FILE = Path.of(
            "src/main/resources/db/analytics-migration/V20261113_1100__init_events_detail_and_pipeline_progress.sql");

    /** 去掉 {@code --} 注释并压缩空白。 */
    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    void partitionedByEventDateWithoutDefault() throws IOException {
        String d = ddl();
        assertThat(d).contains(") PARTITION BY RANGE (event_date);");
        assertThat(d).containsIgnoringCase("CONSTRAINT uq_events_detail_date_msg UNIQUE (event_date, message_id)");
        assertThat(d).doesNotContainIgnoringCase(" DEFAULT;");
        assertThat(d).doesNotContainIgnoringCase("PARTITION OF events_detail DEFAULT");
    }

    @Test
    void indexesOnParent() throws IOException {
        String d = ddl();
        assertThat(d).contains("CREATE INDEX ix_events_detail_user_id ON events_detail (user_id);");
        assertThat(d).contains("CREATE INDEX ix_events_detail_anonymous_id ON events_detail (anonymous_id);");
        assertThat(d).contains("CREATE INDEX ix_events_detail_event ON events_detail (event);");
    }

    @Test
    void initialPartitionsAreFifteenWibDays() throws IOException {
        String d = ddl();
        assertThat(d).contains("(now() AT TIME ZONE 'Asia/Jakarta')::date");
        assertThat(d).contains("FOR i IN -7..7 LOOP");
        assertThat(d).contains("CREATE TABLE IF NOT EXISTS %I PARTITION OF events_detail");
        assertThat(d).contains("'events_detail_p' || to_char(d, 'YYYYMMDD')");
    }

    @Test
    void grantsOnlyDetailParentViaPlaceholder() throws IOException {
        String d = ddl();
        assertThat(d).contains("'GRANT SELECT ON %I TO %I', 'events_detail', '${readerRole}'");
        assertThat(d).doesNotContain("'pipeline_progress', '${readerRole}'");
        assertThat(d).doesNotContain("analytics_reader");
        assertThat(d.split("GRANT", -1)).as("只出现一次 GRANT").hasSize(2);
    }

    @Test
    void progressRowSeeded() throws IOException {
        assertThat(ddl()).contains(
                "INSERT INTO pipeline_progress (pipeline, last_received_at) VALUES ('detail', '1970-01-01 00:00:00+00');");
    }
}
