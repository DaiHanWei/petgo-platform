package com.tailtopia.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 multi-pet Story 3.1 · AC1 · L1（需 postgres + redis；云端不跑，待本地验收）：迁移 {@code V20261018_1600}。
 *
 * <p>空库全量迁移后上下文能起来即 {@code ddl-auto=validate} 通过；表结构 / 两条 FK 级联 / 索引就位；
 * 造存量 Diary 后重跑迁移里的回填语句：每条 {@code pet_id} 非空的帖都有关联行（幂等，重复执行不重复插）。
 */
class ContentPetsMigrationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tableIndexesAndCascadingForeignKeysExist() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'content_pets'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_content_pets_pet_post'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'pk_content_pets'", Long.class)).isEqualTo(1L);
        // 两条外键都 ON DELETE CASCADE（confdeltype = 'c'）。
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid = 'content_pets'::regclass AND contype = 'f' AND confdeltype = 'c'
                """, Long.class)).isEqualTo(2L);
        // 既有按 pet_id 的索引不删不改。
        for (String idx : new String[] {"idx_content_posts_pet_id", "idx_content_posts_pet_recommend",
                "idx_content_posts_pet_cover"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Long.class, idx))
                    .as(idx).isEqualTo(1L);
        }
    }

    @Test
    void backfillLinksEveryPostWithPetIdAndIsIdempotent() throws Exception {
        User u = newUser();
        Long petId = jdbc.queryForObject(
                "INSERT INTO pet_profiles (owner_id, pet_type, name, is_system_default_name, card_token,"
                        + " created_at, updated_at) VALUES (?, 'CAT', 'Lama', false, ?, now(), now()) RETURNING id",
                Long.class, u.getId(), "mig" + SEQ.incrementAndGet());
        // 「存量」：只写 pet_id，不写关联（模拟迁移前的行），含一条软删帖。
        Long live = jdbc.queryForObject("""
                INSERT INTO content_posts (author_id, pet_id, type, text, status, visibility, created_at, updated_at)
                VALUES (?, ?, 'GROWTH_MOMENT', 'lama', 'PUBLISHED', 'PUBLIC', now(), now()) RETURNING id
                """, Long.class, u.getId(), petId);
        Long deleted = jdbc.queryForObject("""
                INSERT INTO content_posts (author_id, pet_id, type, text, status, visibility, deleted_at,
                                           created_at, updated_at)
                VALUES (?, ?, 'GROWTH_MOMENT', 'hapus', 'PUBLISHED', 'PUBLIC', ?, now(), now()) RETURNING id
                """, Long.class, u.getId(), petId, java.sql.Timestamp.from(Instant.now()));

        String backfill = backfillStatement();
        jdbc.update(backfill);
        jdbc.update(backfill); // 幂等

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM content_pets WHERE post_id IN (?, ?) AND pet_profile_id = ?",
                Long.class, live, deleted, petId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM content_posts p
                 WHERE p.pet_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM content_pets cp
                                    WHERE cp.post_id = p.id AND cp.pet_profile_id = p.pet_id)
                """, Long.class)).isZero();

        // 删宠物级联删关联（先按兼容字段解绑，FK fk_content_posts_pet 无 ON DELETE）。
        jdbc.update("UPDATE content_posts SET pet_id = NULL WHERE pet_id = ?", petId);
        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", petId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_pets WHERE pet_profile_id = ?",
                Long.class, petId)).isZero();
    }

    /** 直接取迁移文件里的回填语句执行，保证测的就是上线的那一句。 */
    private static String backfillStatement() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V20261018_1600__create_content_pets.sql"), StandardCharsets.UTF_8);
        int start = sql.indexOf("INSERT INTO content_pets");
        int end = sql.indexOf(';', start);
        return sql.substring(start, end);
    }
}
