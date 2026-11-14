package com.tailtopia.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
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

/** L0：V1.5.0 gift-pack Story 4-1 · AC1 迁移文本守卫（读文件，不连库；照 PetSlotMigrationTextTest）。 */
class EntitlementMigrationTextTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/migration/V20261115_0220__create_entitlement_credits.sql");

    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    private static Set<String> checkValues(String d, String constraint) {
        Matcher m = Pattern.compile(constraint + " CHECK \\(\\w+ IN \\(([^)]*)\\)\\)").matcher(d);
        assertThat(m.find()).as(constraint).isTrue();
        return Arrays.stream(m.group(1).split(",")).map(s -> s.trim().replace("'", "")).collect(Collectors.toSet());
    }

    @Test
    void creditTypeCheckEqualsEnum() throws IOException {
        Set<String> values = checkValues(ddl(), "ck_entitlement_credits_type");
        assertThat(values).hasSize(11);
        assertThat(values).isEqualTo(Arrays.stream(CreditType.values()).map(CreditType::wire)
                .collect(Collectors.toSet()));
    }

    @Test
    void sourceCheckEqualsEnum() throws IOException {
        assertThat(checkValues(ddl(), "ck_entitlement_credits_source"))
                .isEqualTo(Arrays.stream(CreditSource.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void idemUniqueIsKeyAndType() throws IOException {
        assertThat(ddl()).contains("CONSTRAINT uq_entitlement_credits_idem UNIQUE (idem_key, credit_type)");
    }

    @Test
    void noSmallintAndHeaderNotes() throws IOException {
        String raw = Files.readString(FILE, StandardCharsets.UTF_8);
        assertThat(raw.toUpperCase()).doesNotContain("SMALLINT");
        assertThat(raw).contains("EntitlementCreditService").contains("注销不删").contains("KTP_HD:ANY");
        assertThat(raw).doesNotContain("ck_notifications_type").doesNotContain("ck_payment_intents_purpose");
    }

    @Test
    void anyOnlyFromActivityAndUsesCascade() throws IOException {
        String d = ddl();
        assertThat(d).contains("CHECK (credit_type <> 'KTP_HD:ANY' OR source_type = 'ACTIVITY')");
        assertThat(d).contains("REFERENCES entitlement_credits (id) ON DELETE CASCADE");
        assertThat(d).contains("CONSTRAINT uq_entitlement_credit_uses_key_credit UNIQUE (use_key, credit_id)");
    }
}
