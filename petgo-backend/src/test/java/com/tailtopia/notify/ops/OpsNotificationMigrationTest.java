package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * L0：V1.3.4 Story 6.1 · AC1 迁移文本守卫（读文件，不连库）。
 */
class OpsNotificationMigrationTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/migration/V20261012_1107__create_ops_notification_campaigns.sql");

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
    void audienceModeCheckRejectsMissingOrNullMode() throws IOException {
        // 🔴 只写 `audience_spec ->> 'mode' IN (...)` 拦不住缺 mode：->> 返回 NULL，NULL IN (...) 为 NULL，CHECK 视为通过。
        assertThat(ddl()).contains("CONSTRAINT ck_ops_notification_campaigns_audience_mode CHECK ( "
                + "audience_spec ? 'mode' AND COALESCE(audience_spec ->> 'mode', '') IN ('ALL', 'FILTER', 'IDS'))");
    }

    @Test
    void targetColumnsFollowOpsPopupsShape() throws IOException {
        String d = ddl();
        assertThat(d).contains("target_type VARCHAR(16) NOT NULL DEFAULT 'NONE'");
        assertThat(d).contains("target_value VARCHAR(512) NULL");
        assertThat(d).contains("CHECK ((target_type = 'NONE') = (target_value IS NULL))");
        assertThat(d).contains("public_token VARCHAR(32) NOT NULL");
    }

    @Test
    void countsMigrationAddsNullableColumnsOnly() throws IOException {
        // V1.3.4 AB-31 返工：新增 ALTER（不改 1107）；三列可空，存量行不受影响
        Path f = Path.of("src/main/resources/db/migration/V20261015_1209__ops_notification_campaigns_counts.sql");
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(f, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        String d = sb.toString().replaceAll("\\s+", " ");
        assertThat(d).contains("ADD COLUMN estimated_count INT NULL")
                .contains("ADD COLUMN planned_count INT NULL")
                .contains("ADD COLUMN ids_source VARCHAR(8) NULL")
                .contains("CHECK (ids_source IS NULL OR ids_source IN ('PASTE', 'CSV'))")
                .doesNotContain("NOT NULL");
    }
}
