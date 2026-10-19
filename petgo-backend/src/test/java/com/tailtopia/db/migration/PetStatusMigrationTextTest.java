package com.tailtopia.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0：V1.4.0 multi-pet Story 2.1 · AC1 迁移文本守卫（读文件，不连库；照 PetSlotMigrationTextTest）。 */
class PetStatusMigrationTextTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/migration/V20261018_1604__pet_profiles_status_keep_active.sql");

    private static String ddl() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readString(FILE, StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf("--");
            sb.append(c >= 0 ? line.substring(0, c) : line).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ");
    }

    @Test
    void addsStatusWithCheckKeepActiveAndIndex() throws IOException {
        String d = ddl();
        assertThat(d).contains(
                "ALTER TABLE pet_profiles ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';");
        assertThat(d).contains("ADD CONSTRAINT ck_pet_profiles_status CHECK (status IN ('ACTIVE', 'READ_ONLY'));");
        assertThat(d).contains("ALTER TABLE pet_profiles ADD COLUMN keep_active BOOLEAN NOT NULL DEFAULT false;");
        assertThat(d).contains("ix_pet_profiles_owner_status ON pet_profiles (owner_id, status);");
        assertThat(d).contains("COMMENT ON COLUMN pet_profiles.status").contains("PetSlotStateService.reconcile");
        assertThat(d).contains("COMMENT ON COLUMN pet_profiles.keep_active").contains("Ubah pilihan");
    }

    /** 不回填、不碰共享 CHECK。 */
    @Test
    void noBackfillAndTouchesNothingElse() throws IOException {
        String raw = Files.readString(FILE, StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("ck_notifications_type").doesNotContain("ck_payment_intents_purpose");
        String d = ddl();
        assertThat(d).doesNotContain("UPDATE ").doesNotContain("INSERT ").doesNotContain("DELETE ")
                .doesNotContain("DROP ");
    }
}
