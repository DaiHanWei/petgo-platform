package com.tailtopia.healthreminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderRoute;
import com.tailtopia.notify.domain.NotificationType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** L0 守卫（V1.5.0 Story 2.1 · AC2）：提醒目录 ↔ 地基 NotificationType ↔ 迁移 CHECK ↔ PRD 附录 C。 */
class ReminderKindTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V20261115_0120__create_health_reminder_tables.sql");

    private static Set<String> checkValues(String constraint) throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).replaceAll("(?m)--.*$", "");
        Matcher m = Pattern.compile("CONSTRAINT " + constraint + " CHECK \\(([^;]*?)\\)\\)", Pattern.DOTALL)
                .matcher(sql);
        assertThat(m.find()).as(constraint).isTrue();
        Set<String> out = new LinkedHashSet<>();
        Matcher v = Pattern.compile("'([A-Za-z_0-9]+)'").matcher(m.group(1));
        while (v.find()) {
            out.add(v.group(1));
        }
        return out;
    }

    @Test
    void twentyThreeKindsMatchFoundationNotificationTypesOneToOne() {
        assertThat(ReminderKind.values()).hasSize(23);
        List<String> health = Arrays.stream(NotificationType.values()).map(Enum::name)
                .filter(n -> n.startsWith("HEALTH_")).map(n -> n.substring("HEALTH_".length())).toList();
        assertThat(health).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ReminderKind.values()).map(Enum::name).toList());
        for (ReminderKind k : ReminderKind.values()) {
            assertThat(k.notificationType().name()).isEqualTo("HEALTH_" + k.name());
        }
    }

    @Test
    void enumsMatchMigrationChecks() throws Exception {
        assertThat(checkValues("ck_health_reminders_kind")).containsExactly(
                Arrays.stream(ReminderKind.values()).map(Enum::name).toArray(String[]::new));
        assertThat(checkValues("ck_health_reminders_point")).containsExactly(
                Arrays.stream(PushPoint.values()).map(Enum::name).toArray(String[]::new));
        assertThat(checkValues("ck_health_reminders_origin")).containsExactly(
                Arrays.stream(ReminderOrigin.values()).map(Enum::name).toArray(String[]::new));
        assertThat(checkValues("ck_health_reminders_reason")).containsExactly(
                Arrays.stream(CancelReason.values()).map(CancelReason::wire).toArray(String[]::new));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "VACCINE_D_MINUS_7, 3, true, PAGE",
        "VACCINE_D_DAY, 3, false, FORM",
        "VACCINE_D_PLUS_7, 3, true, FORM",
        "FIRST_VACCINE, 8, true, FORM_DATE_EMPTY",
        "DEWORM_INTERNAL_D_DAY, 4, false, FORM",
        "DEWORM_EXTERNAL_D_DAY, 4, false, FORM",
        "DEWORM_FLEA_WORM_D_DAY, 4, false, FORM",
        "DEWORM_HEARTWORM_D_DAY, 4, false, FORM",
        "DEWORM_INTERNAL_D_PLUS_3, 4, true, FORM",
        "DEWORM_EXTERNAL_D_PLUS_3, 4, true, FORM",
        "DEWORM_LEGACY_D_DAY, 4, false, FORM",
        "DEWORM_LEGACY_D_PLUS_3, 4, true, FORM",
        "FIRST_DEWORM, 8, true, FORM_DATE_EMPTY",
        "VET_VISIT_D_MINUS_1, 2, false, PAGE",
        "VET_VISIT_D_DAY, 2, false, FORM",
        "STITCH_CHECK, 5, true, PAGE",
        "HEAT_DOG_D_MINUS_7, 6, true, PAGE",
        "PYOMETRA_WATCH, 1, false, PAGE",
        "HEAT_CAT_SECOND, 8, true, PAGE",
        "HEAT_MALE_FIRST, 8, true, PAGE",
        "SPAY_NEUTER_SUGGESTION, 8, true, FORM_DATE_EMPTY",
        "CHECKUP, 7, true, FORM_DATE_EMPTY",
        "CUSTOM_D_DAY, 2, false, FORM"})
    void appendixCRow(ReminderKind kind, int tier, boolean movable, ReminderRoute route) {
        assertThat(kind.tier()).isEqualTo(tier);
        assertThat(kind.movable()).isEqualTo(movable);
        assertThat(kind.route()).isEqualTo(route);
    }

    @Test
    void wireValuesAreLowerSnake() {
        assertThat(PushPoint.D_MINUS_7.wire()).isEqualTo("d_minus_7");
        assertThat(CancelReason.SKIPPED_SCHEDULED_PUSH.wire()).isEqualTo("skipped_scheduled_push");
        assertThat(ReminderOrigin.LAUNCH_QUEUE.wire()).isEqualTo("launch_queue");
        assertThat(ReminderKind.VACCINE_D_DAY.wire()).isEqualTo("vaccine_d_day");
    }
}
