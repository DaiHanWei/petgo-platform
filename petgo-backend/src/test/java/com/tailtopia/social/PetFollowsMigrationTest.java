package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** L0：V1.4.2 social-topic Story 1.1 · AC1 迁移 ① 文本守卫（读文件，不连库）。 */
class PetFollowsMigrationTest {

    private static final Path FILE =
            Path.of("src/main/resources/db/migration/V20261104_2000__create_pet_follows.sql");

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
    void tableShape() throws IOException {
        String d = ddl();
        assertThat(d).contains("CREATE TABLE pet_follows")
                .contains("follower_id BIGINT NOT NULL")
                .contains("pet_profile_id BIGINT NOT NULL")
                .contains("created_at TIMESTAMPTZ NOT NULL DEFAULT now()")
                .contains("CONSTRAINT pk_pet_follows PRIMARY KEY (follower_id, pet_profile_id)")
                .contains("CONSTRAINT fk_pet_follows_follower FOREIGN KEY (follower_id) REFERENCES users (id) ON DELETE CASCADE")
                .contains("CONSTRAINT fk_pet_follows_pet FOREIGN KEY (pet_profile_id) REFERENCES pet_profiles (id) ON DELETE CASCADE");
    }

    @Test
    void indexes() throws IOException {
        assertThat(ddl())
                .contains("CREATE INDEX idx_pet_follows_pet_created ON pet_follows (pet_profile_id, created_at)")
                .contains("CREATE INDEX idx_pet_follows_follower_created ON pet_follows (follower_id, created_at)");
    }

    @Test
    void followerCountColumnIsInteger() throws IOException {
        assertThat(ddl()).contains("ALTER TABLE pet_profiles ADD COLUMN follower_count INTEGER NOT NULL DEFAULT 0");
    }

    /** Hibernate {@code int → int4}：整个文件（含注释）都不许出现 SMALLINT。 */
    @Test
    void noSmallint() throws IOException {
        assertThat(Files.readString(FILE, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT)).doesNotContain("SMALLINT");
    }

    /** 新功能不回填：没有 INSERT / UPDATE。 */
    @Test
    void noBackfill() throws IOException {
        String d = ddl().toUpperCase(Locale.ROOT);
        assertThat(d).doesNotContain("INSERT INTO").doesNotContain("UPDATE ");
    }
}
