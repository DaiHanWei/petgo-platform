package com.tailtopia.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.moderation.domain.ReportReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 1-5 · AC1 迁移文本守卫。 */
class GiftMessageReportMigrationTextTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/migration/V20261115_0210__create_gift_message_reports.sql");

    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    private static Set<String> values(String d, String constraint) {
        Matcher m = Pattern.compile(constraint + " CHECK \\(\\w+ IN \\(([^)]*)\\)\\)").matcher(d);
        assertThat(m.find()).as(constraint).isTrue();
        return Arrays.stream(m.group(1).split(",")).map(s -> s.trim().replace("'", "")).collect(Collectors.toSet());
    }

    @Test
    void reasonCheckEqualsReportReason() throws IOException {
        assertThat(values(ddl(), "ck_gift_message_reports_reason"))
                .isEqualTo(Arrays.stream(ReportReason.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void statusThreeValuesAndUniqueKey() throws IOException {
        String d = ddl();
        assertThat(values(d, "ck_gift_message_reports_status")).containsExactlyInAnyOrder("PENDING", "DISMISSED", "ACTIONED");
        assertThat(d).contains("CONSTRAINT uq_gift_message_reports_reporter UNIQUE (gift_order_id, reporter_user_id)");
        assertThat(d).contains("REFERENCES gift_orders (id) ON DELETE CASCADE");
        assertThat(d).contains("CREATE INDEX ix_gift_message_reports_status ON gift_message_reports (status, created_at);");
    }

    @Test
    void noSmallintNoSharedChecks() throws IOException {
        String raw = Files.readString(FILE, StandardCharsets.UTF_8);
        assertThat(raw.toUpperCase()).doesNotContain("SMALLINT");
        assertThat(raw).doesNotContain("ck_notifications_type").doesNotContain("content_reports")
                .doesNotContain("place_reports (");
    }
}
