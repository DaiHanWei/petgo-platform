package com.tailtopia.healthreminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0 迁移文本守卫（V1.5.0 健康记录提醒 Story 1.1 · AC1 / AC2）：不连库，只读 SQL 文本。
 *
 * <p>钉住：① 健康记录类型约束 = V76 的 5 值 + VET_VISIT，且 V76 之后只有本线这一条重建；② 整数列不用 SMALLINT
 * （实体 Integer ↔ int4，validate 会红）；③ 本线迁移不碰地基所有的通知类型约束。
 */
class HealthReminderMigrationTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");
    private static final Path RECORDS = DIR.resolve("V20261115_0100__health_records_reminder_fields.sql");
    private static final Path PETS = DIR.resolve("V20261115_0110__pet_profiles_health_status_fields.sql");

    private static String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 去掉 {@code --} 行注释，只看可执行 SQL。 */
    private static String sqlOnly(String text) {
        return text.replaceAll("(?m)--.*$", "");
    }

    @Test
    void healthRecordTypeConstraintIsV76SetPlusVetVisit() throws Exception {
        String sql = sqlOnly(read(RECORDS));
        Matcher m = Pattern.compile("ADD CONSTRAINT ck_health_records_type\\s+CHECK \\(type IN \\(([^)]*)\\)\\)")
                .matcher(sql);
        assertThat(m.find()).isTrue();
        Set<String> values = new LinkedHashSet<>();
        Matcher v = Pattern.compile("'([A-Z_]+)'").matcher(m.group(1));
        while (v.find()) {
            values.add(v.group(1));
        }
        assertThat(values).containsExactly("VACCINE", "DEWORM", "MENSTRUATION", "NEUTER", "CUSTOM", "VET_VISIT");
        assertThat(sql).contains("DROP CONSTRAINT IF EXISTS ck_health_records_type");
    }

    @Test
    void onlyV76AndThisMigrationDefineTheTypeConstraint() throws Exception {
        try (Stream<Path> files = Files.list(DIR)) {
            assertThat(files.filter(p -> {
                try {
                    return sqlOnly(read(p)).contains("ck_health_records_type");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).map(p -> p.getFileName().toString()).sorted().toList())
                    .containsExactly("V20261115_0100__health_records_reminder_fields.sql",
                            "V76__init_health_records.sql");
        }
    }

    @Test
    void noSmallintAndRecordOriginDefaults() throws Exception {
        String sql = sqlOnly(read(RECORDS));
        assertThat(sql.toUpperCase(Locale.ROOT)).doesNotContain("SMALLINT");
        assertThat(sql).contains("dose                    INTEGER");
        assertThat(sql).contains("product_duration_months INTEGER");
        // 存量行一次性落 BACKFILL，之后默认 APP。
        assertThat(sql).contains("record_origin           VARCHAR(16) NOT NULL DEFAULT 'BACKFILL'");
        assertThat(sql).contains("ALTER COLUMN record_origin SET DEFAULT 'APP'");
        assertThat(sql).contains("CHECK (record_origin IN ('APP','LEGACY_APP','BACKFILL'))");
    }

    @Test
    void petProfileColumns() throws Exception {
        String sql = sqlOnly(read(PETS));
        assertThat(sql).contains("cat_in_home      BOOLEAN,")
                .contains("pregnant_nursing BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("pregnant_set_at  TIMESTAMPTZ")
                .contains("sex_set_at       TIMESTAMPTZ");
        assertThat(sql).doesNotContain("UPDATE ");
    }

    @Test
    void lineMigrationsNeverTouchNotificationTypeConstraint() throws Exception {
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path p : files.filter(f -> f.getFileName().toString().startsWith("V20261115_01")).toList()) {
                String sql = sqlOnly(read(p));
                assertThat(sql).as(p.toString()).doesNotContain("ck_notifications_type")
                        .doesNotContain("ck_user_push_mutes_category");
            }
        }
    }

    // ---- Story 2.1：V20261115_0120 ----

    private static final Path TABLES = DIR.resolve("V20261115_0120__create_health_reminder_tables.sql");

    @Test
    void reminderTablesShape() throws Exception {
        String sql = sqlOnly(read(TABLES));
        assertThat(sql).contains("CREATE TABLE health_reminder_settings")
                .contains("INSERT INTO health_reminder_settings (id) VALUES (1);")
                .contains("CREATE TABLE health_notifications")
                .contains("CREATE TABLE health_reminders")
                .contains("CREATE TABLE health_reminder_user_launch")
                .contains("CREATE UNIQUE INDEX ux_health_reminders_once ON health_reminders")
                .contains("(pet_profile_id, COALESCE(source_record_id, 0), reminder_kind, push_point, due_date)")
                .contains("WHERE status IN ('PENDING', 'SENT');")
                .contains("'old_app'");
        assertThat(sql.toUpperCase(Locale.ROOT)).doesNotContain("SMALLINT");
    }

    // ---- Story 2.2：V20261115_0130 ----

    @Test
    void vetConsultVisitsTableAndBackfillUseRealColumns() throws Exception {
        String sql = sqlOnly(read(DIR.resolve("V20261115_0130__create_health_vet_consult_visits.sql")));
        assertThat(sql).contains("CREATE TABLE health_vet_consult_visits")
                .contains("session_id     BIGINT PRIMARY KEY")
                .contains("co.consult_session_id = s.id")
                .contains("JOIN pet_profiles p ON p.id = co.pet_profile_id")
                .contains("AT TIME ZONE 'Asia/Jakarta'")
                .contains("WHERE s.status = 'CLOSED'")
                .contains("ON CONFLICT (session_id) DO NOTHING");
    }
}
