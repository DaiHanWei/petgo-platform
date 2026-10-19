package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0（V1.4.0 ① Story 3.4 · AC1）：作者等级加权迁移的文本守卫；默认值与 CHECK 的实际效果由 L1 验。 */
class AuthorLevelBoostMigrationTextTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");
    private static final Path MIGRATION = DIR.resolve("V20261018_1501__feed_rank_author_level_boost.sql");

    @Test
    void addsDoublePrecisionColumnWithDefaultAndRangeCheck() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        // DOUBLE PRECISION 对齐实体 double（不用架构写的 NUMERIC(4,2)：ddl-auto=validate 会拒绝启动）。
        assertThat(sql).contains("ADD COLUMN author_level_boost DOUBLE PRECISION NOT NULL DEFAULT 1.05")
                .contains("CHECK (author_level_boost >= 1.00 AND author_level_boost <= 2.00)")
                .doesNotContain("author_level_boost NUMERIC");
    }

    @Test
    void usesThePreAssignedVersion() throws IOException {
        try (var files = Files.list(DIR)) {
            assertThat(files.map(p -> p.getFileName().toString())
                    .filter(n -> n.contains("author_level_boost")))
                    .containsExactly("V20261018_1501__feed_rank_author_level_boost.sql");
        }
    }
}
