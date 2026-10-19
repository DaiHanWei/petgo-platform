package com.tailtopia.content.support;

import com.tailtopia.content.domain.ContentPet;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.repository.ContentPetRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 multi-pet Story 3.1 · L1 夹具：绕过 {@code ContentService.publish} 直接造 Diary 的测试，
 * 按宠物的读口改读 {@code content_pets} 后须同步写关联行（等价于生产上发布接口 / 迁移回填所做的事）。
 */
public final class ContentPetsTestFixture {

    private ContentPetsTestFixture() {
    }

    /** 按帖子的 {@code pet_id}（兼容字段）补一行关联；{@code pet_id} 为空则不写。 */
    public static ContentPost link(ContentPetRepository contentPets, ContentPost saved) {
        if (saved.getPetId() != null) {
            contentPets.save(ContentPet.of(saved.getId(), saved.getPetId()));
        }
        return saved;
    }

    /** 与迁移 {@code V20261018_1600} 同一句回填：把所有带 {@code pet_id} 的帖补进关联表（幂等）。 */
    public static void backfillFromPetId(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO content_pets (post_id, pet_profile_id)
                SELECT id, pet_id FROM content_posts WHERE pet_id IS NOT NULL
                ON CONFLICT DO NOTHING
                """);
    }

    /** 追加一只关联宠物（多宠帖）。 */
    public static void linkPet(JdbcTemplate jdbc, long postId, long petId) {
        jdbc.update("INSERT INTO content_pets (post_id, pet_profile_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                postId, petId);
    }
}
