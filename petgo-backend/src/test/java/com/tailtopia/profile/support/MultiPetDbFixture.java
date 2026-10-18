package com.tailtopia.profile.support;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.0 multi-pet L1 夹具：库里仍有 {@code uq_pet_profiles_owner_id}（Story 1.4 才删）时，临时删约束造双宠物。
 *
 * <p>用法：{@code @BeforeEach} 调 {@link #dropOwnerUniqueIfPresent}，{@code @AfterEach} 的 {@code finally} 里调
 * {@link #restoreOwnerUnique}。只有 drop 返回 true 时 restore 才会加回约束——1.4 迁移落地后约束本就不存在，
 * 这些测试照常可跑且不会把约束加回来。
 */
public final class MultiPetDbFixture {

    private static final SecureRandom RANDOM = new SecureRandom();

    private MultiPetDbFixture() {
    }

    /** 返回本次是否真的删了约束（1.4 迁移之后约束已不存在 → false，restore 自然 no-op）。 */
    public static boolean dropOwnerUniqueIfPresent(JdbcTemplate jdbc) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_pet_profiles_owner_id'", Integer.class);
        if (n != null && n > 0) {
            jdbc.execute("ALTER TABLE pet_profiles DROP CONSTRAINT uq_pet_profiles_owner_id");
            return true;
        }
        return false;
    }

    /** 先删掉本测试造的多余宠物（含派生行），再按原定义加回约束。 */
    public static void restoreOwnerUnique(JdbcTemplate jdbc, boolean dropped, Collection<Long> extraPetIds) {
        for (Long petId : extraPetIds) {
            deletePetCascade(jdbc, petId);
        }
        if (dropped) {
            jdbc.execute("ALTER TABLE pet_profiles ADD CONSTRAINT uq_pet_profiles_owner_id UNIQUE (owner_id)");
        }
    }

    /** 直插一只宠物（不走 {@code POST /pet-profiles}，那条在 1.4 前仍 409）；{@code created_at} 显式给。 */
    public static long insertPet(JdbcTemplate jdbc, long ownerId, String name, String petType, Instant createdAt) {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        String token = "mp" + HexFormat.of().formatHex(b);
        Timestamp ts = Timestamp.from(createdAt);
        return jdbc.queryForObject(
                "INSERT INTO pet_profiles (owner_id, pet_type, name, is_system_default_name, card_token,"
                        + " created_at, updated_at) VALUES (?, ?, ?, false, ?, ?, ?) RETURNING id",
                Long.class, ownerId, petType, name, token, ts, ts);
    }

    /** 删一只测试宠物及其无级联派生行（顺序：子表 → 宠物）。 */
    private static void deletePetCascade(JdbcTemplate jdbc, long petId) {
        jdbc.update("DELETE FROM milestone_completions WHERE pet_milestone_id IN"
                + " (SELECT id FROM pet_milestones WHERE pet_profile_id = ?)", petId);
        jdbc.update("DELETE FROM milestone_shares WHERE pet_profile_id = ?", petId);
        jdbc.update("DELETE FROM pet_milestones WHERE pet_profile_id = ?", petId);
        jdbc.update("DELETE FROM health_records WHERE pet_profile_id = ?", petId);
        jdbc.update("DELETE FROM health_events WHERE pet_id = ?", petId);
        jdbc.update("UPDATE content_posts SET pet_id = NULL WHERE pet_id = ?", petId);
        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", petId);
    }
}
