package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * L0：原始层迁移文本守卫（analytics-pipeline Story 1.3 AC1；读文件，不连库）。
 *
 * <p>测试库没有 {@code analytics_reader} 角色，「不授权」改为断言迁移文本不含 {@code GRANT}。
 */
class EventsRawMigrationTextTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/analytics-migration/V20261113_1020__create_events_raw.sql");

    /** 去掉 {@code --} 注释并压缩空白。 */
    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    private static int count(String haystack, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(haystack);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void fileExists() {
        assertThat(Files.exists(FILE)).isTrue();
    }

    @Test
    void neverGrantsRawLayer() throws IOException {
        String raw = Files.readString(FILE, StandardCharsets.UTF_8);
        assertThat(raw.toUpperCase()).doesNotContain("GRANT");
        assertThat(raw).doesNotContain("${readerRole}");
    }

    @Test
    void partitionedByReceivedAtWithDefaultOnly() throws IOException {
        String d = ddl();
        assertThat(d).contains(") PARTITION BY RANGE (received_at);");
        assertThat(d).contains("CREATE TABLE events_raw_pdefault PARTITION OF events_raw DEFAULT;");
        // 日期分区一律由 PartitionMaintenanceJob 建，迁移里不写死日期
        assertThat(d).doesNotContainIgnoringCase("FOR VALUES");
        assertThat(d).doesNotContainPattern("events_raw_p\\d{8}");
    }

    @Test
    void onlyBrinOnReceivedAt() throws IOException {
        String d = ddl();
        assertThat(count(d, "CREATE\\s+(UNIQUE\\s+)?INDEX")).isEqualTo(1);
        assertThat(d).contains("CREATE INDEX ix_events_raw_received_at_brin ON events_raw USING brin (received_at);");
        assertThat(d).doesNotContainIgnoringCase("PRIMARY KEY").doesNotContainIgnoringCase("UNIQUE");
    }

    @Test
    void columnsAlignWithSegment() throws IOException {
        String d = ddl();
        for (String col : new String[] {"message_id varchar(64) NOT NULL", "event varchar(128) NOT NULL",
                "client_ts timestamptz NOT NULL", "sent_at timestamptz NOT NULL", "received_at timestamptz NOT NULL",
                "anonymous_id varchar(64),", "user_key varchar(64),", "user_id bigint,", "source varchar(8) NOT NULL",
                "properties jsonb NOT NULL DEFAULT '{}'::jsonb", "context jsonb NOT NULL DEFAULT '{}'::jsonb",
                "identity_unverified boolean NOT NULL DEFAULT false", "unknown_event boolean NOT NULL DEFAULT false"}) {
            assertThat(d).contains(col);
        }
        assertThat(d).contains("CONSTRAINT ck_events_raw_source CHECK (source IN ('APP', 'SERVER', 'H5'))");
        assertThat(d).doesNotContain("Asia/Jakarta");
    }
}
