package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0（修复线 2026-11-12）：优质老帖池 / 互动冷却 / 看过暂不出现迁移的文本守卫；默认值与 CHECK 的实际效果由 L1 验。 */
class QualityPoolMigrationTextTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V20261112_1815__feed_rank_quality_pool_and_seen_hide.sql");

    @Test
    void addsFourIntegerColumnsWithDefaultsChecksAndUserIndexes() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ADD COLUMN quality_pool_days        INTEGER NOT NULL DEFAULT 30")
                .contains("ADD COLUMN quality_pool_size        INTEGER NOT NULL DEFAULT 300")
                .contains("ADD COLUMN interacted_cooldown_days INTEGER NOT NULL DEFAULT 3")
                .contains("ADD COLUMN seen_hide_hours          INTEGER NOT NULL DEFAULT 24")
                .contains("CHECK (quality_pool_days BETWEEN 1 AND 180)")
                .contains("CHECK (quality_pool_size BETWEEN 0 AND 2000)")
                .contains("CHECK (interacted_cooldown_days BETWEEN 0 AND 30)")
                .contains("CHECK (seen_hide_hours BETWEEN 0 AND 240)")
                .contains("ON content_likes (user_id, created_at)")
                .contains("ON comments (author_id, created_at)");
    }
}
