package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0（V1.3.4 Story 1.2 · AC1.3）：科普半衰期迁移的文本守卫；默认值与 CHECK 的实际效果由 L1 验。 */
class EduHalfLifeMigrationTextTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V20261012_1102__feed_rank_edu_half_life.sql");

    @Test
    void addsColumnWithDefault720AndPositiveCheck() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("ADD COLUMN edu_freshness_half_life_hours DOUBLE PRECISION NOT NULL DEFAULT 720")
                .contains("CHECK (edu_freshness_half_life_hours > 0)");
        // 与 RankParams 的默认值是同一个数（两处各写一份，钉住不漂）
        assertThat(RankParams.DEFAULT_EDU_FRESHNESS_HALF_LIFE_HOURS).isEqualTo(720d);
    }
}
