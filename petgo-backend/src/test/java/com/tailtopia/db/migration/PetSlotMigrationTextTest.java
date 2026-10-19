package com.tailtopia.db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0：V1.4.0 multi-pet Story 1.4 · AC3 迁移文本守卫（读文件，不连库；照 OpsNotificationMigrationTest）。 */
class PetSlotMigrationTextTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/migration/V20261018_1603__drop_uq_pet_profiles_owner_id.sql");

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
    void fileExists() {
        assertThat(Files.exists(FILE)).isTrue();
    }

    @Test
    void dropsOwnerUniqueAndAddsPlainIndex() throws IOException {
        String d = ddl();
        assertThat(d).contains("ALTER TABLE pet_profiles DROP CONSTRAINT IF EXISTS uq_pet_profiles_owner_id;");
        assertThat(d).contains(
                "CREATE INDEX IF NOT EXISTS idx_pet_profiles_owner_created ON pet_profiles (owner_id, created_at, id);");
        assertThat(d).doesNotContain("UNIQUE");
    }

    @Test
    void touchesNothingElse() throws IOException {
        String raw = Files.readString(FILE, StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("ck_notifications_type").doesNotContain("ck_payment_intents_purpose")
                .doesNotContain("uq_pet_profiles_card_token").doesNotContain("fk_pet_profiles_owner");
        String d = ddl();
        assertThat(d).doesNotContain("UPDATE ").doesNotContain("INSERT ").doesNotContain("DELETE ");
    }
}
