package com.tailtopia.ops.popup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0：V1.3.4 Story 5.1 · AC1 迁移文本守卫。读文件断言，不连库（文件读取方式同 {@code PlaceSchemaAlignmentTest}）。
 */
class OpsPopupMigrationTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final String FILE = "V20261012_1106__create_ops_popups.sql";

    private static String sql() throws IOException {
        return Files.readString(MIGRATIONS.resolve(FILE), StandardCharsets.UTF_8);
    }

    /** 去掉 {@code --} 注释并压缩空白，便于对 DDL 片段做稳健匹配。 */
    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : sql().split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    void fileNameIsThePreallocatedOneAndUnique() throws IOException {
        assertThat(Files.exists(MIGRATIONS.resolve(FILE))).isTrue();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            List<String> creators = files.filter(p -> {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8).contains("CREATE TABLE ops_popups");
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }).map(p -> p.getFileName().toString()).toList();
            assertThat(creators).containsExactly(FILE);
        }
    }

    @Test
    void createsBothTables() throws IOException {
        String d = ddl();
        assertThat(d).contains("CREATE TABLE ops_popups (");
        assertThat(d).contains("CREATE TABLE ops_popup_impressions (");
    }

    @Test
    void nullabilityFollowsD23() throws IOException {
        String d = ddl();
        assertThat(d).contains("public_token VARCHAR(32) NOT NULL");
        assertThat(d).contains("ends_at TIMESTAMPTZ NULL");
        assertThat(d).contains("image_url_id VARCHAR(512) NOT NULL");
        assertThat(d).contains("image_url_en VARCHAR(512) NULL");
        assertThat(d).contains("title_id VARCHAR(120) NULL");
        assertThat(d).contains("title_en VARCHAR(120) NULL");
        assertThat(d).contains("body_id VARCHAR(255) NULL");
        assertThat(d).contains("body_en VARCHAR(255) NULL");
        assertThat(d).contains("button_text_id VARCHAR(40) NOT NULL");
        assertThat(d).contains("button_text_en VARCHAR(40) NULL");
        assertThat(d).contains("target_value VARCHAR(512) NULL");
        assertThat(d).contains("status VARCHAR(16) NOT NULL DEFAULT 'DISABLED'");
        assertThat(d).contains("new_user_days INT NOT NULL DEFAULT 7");
    }

    @Test
    void checkConstraintsPinNamesAndValues() throws IOException {
        String d = ddl();
        assertThat(d).contains("CONSTRAINT uq_ops_popups_public_token UNIQUE (public_token)");
        assertThat(d).contains("CONSTRAINT ck_ops_popups_status CHECK (status IN ('ACTIVE', 'DISABLED'))");
        assertThat(d).contains("CONSTRAINT ck_ops_popups_audience CHECK (audience IN ('ALL', 'NEW', 'OLD'))");
        assertThat(d).contains("CONSTRAINT ck_ops_popups_frequency CHECK (frequency IN ('ONCE', 'DAILY'))");
        assertThat(d).contains("CONSTRAINT ck_ops_popups_new_user_days CHECK (new_user_days > 0)");
        assertThat(d).contains("CONSTRAINT ck_ops_popups_window CHECK (ends_at IS NULL OR ends_at > starts_at)");
        assertThat(d).contains("CONSTRAINT ck_ops_popups_target_type CHECK (target_type IN "
                + "('NONE', 'PRODUCT', 'CATEGORY', 'APP_PAGE', 'EXTERNAL'))");
        assertThat(d).contains("CONSTRAINT ck_ops_popups_target_value CHECK ((target_type = 'NONE') = (target_value IS NULL))");
    }

    @Test
    void bothForeignKeysCascade() throws IOException {
        String d = ddl();
        assertThat(d).contains("CONSTRAINT fk_ops_popup_impressions_popup FOREIGN KEY (popup_id) "
                + "REFERENCES ops_popups (id) ON DELETE CASCADE");
        assertThat(d).contains("CONSTRAINT fk_ops_popup_impressions_user FOREIGN KEY (user_id) "
                + "REFERENCES users (id) ON DELETE CASCADE");
    }

    @Test
    void indexesAndNoConcurrently() throws IOException {
        String d = ddl();
        assertThat(d).contains("CREATE INDEX idx_ops_popups_active_pick ON ops_popups "
                + "(priority DESC, created_at DESC, id DESC) WHERE status = 'ACTIVE'");
        assertThat(d).contains("CREATE INDEX idx_ops_popup_impressions_user_popup ON ops_popup_impressions "
                + "(user_id, popup_id, shown_at DESC)");
        assertThat(sql()).doesNotContainIgnoringCase("CONCURRENTLY");
        assertThat(d).doesNotContainIgnoringCase("INSERT INTO");
    }
}
