package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** L0（V1.3.4 Story 1.1 · AC4）：教育池部分索引迁移的文本守卫；建出来的索引由 L1 查 pg_indexes。 */
class EduPoolMigrationTextTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V20261012_1101__content_posts_knowledge_index.sql");

    @Test
    void createsTheKnowledgePartialIndexWithoutConcurrently() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains("CREATE INDEX idx_content_posts_knowledge_feed")
                .contains("ON content_posts (created_at DESC, id DESC)")
                .contains("WHERE type = 'KNOWLEDGE' AND deleted_at IS NULL");
        // 🔴 Flyway 迁移在事务里执行，CONCURRENTLY 会直接报错
        assertThat(sql.lines().filter(l -> !l.trim().startsWith("--")))
                .noneMatch(l -> l.toUpperCase().contains("CONCURRENTLY"));
    }
}
