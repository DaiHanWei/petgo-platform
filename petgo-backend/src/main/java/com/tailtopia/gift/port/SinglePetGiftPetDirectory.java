package com.tailtopia.gift.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link GiftPetDirectory} 的<b>单宠过渡实现</b>（V1.4.0 gift Story 1.1 · AD-5 / P-3），对齐 story 4.1 删除并换 ② 实现。
 *
 * <p>🔴 用 {@link NamedParameterJdbcTemplate} 直接读 {@code pet_profiles} JOIN {@code users}（写法照地基
 * {@code GrowthQueryService}），<b>不改、不新增 profile 模块任何类</b> —— ② 正在大改 profile，避免冲突。
 * 送礼模块里只有本类允许出现这两张表名（{@code GiftModuleBoundaryGuardTest} 钉着）。
 */
@Component
public class SinglePetGiftPetDirectory implements GiftPetDirectory {

    static final String PET_SQL = """
            SELECT p.id, p.owner_id, p.name, p.avatar_url, p.pet_type, p.breed, u.status, u.deleted_at
              FROM pet_profiles p
              JOIN users u ON u.id = p.owner_id
             WHERE p.id = :petId
            """;

    /** 单宠过渡：只有公开、已发布、未举报下架、未删除、带宠物的 Diary（GROWTH_MOMENT）帖才有收礼宠物。 */
    static final String POST_PET_SQL = """
            SELECT pet_id
              FROM content_posts
             WHERE id = :postId
               AND type = 'GROWTH_MOMENT'
               AND deleted_at IS NULL
               AND status = 'PUBLISHED'
               AND visibility = 'PUBLIC'
               AND report_hidden_at IS NULL
               AND pet_id IS NOT NULL
            """;

    private static final RowMapper<GiftPet> PET_ROW = (rs, i) -> {
        boolean deleted = rs.getTimestamp("deleted_at") != null;
        return new GiftPet(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"),
                rs.getString("avatar_url"), rs.getString("pet_type"), rs.getString("breed"),
                !deleted && "ACTIVE".equals(rs.getString("status")), deleted);
    };

    private final NamedParameterJdbcTemplate jdbc;

    public SinglePetGiftPetDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<GiftPet> pet(long petId) {
        return jdbc.query(PET_SQL, Map.of("petId", petId), PET_ROW).stream().findFirst();
    }

    @Override
    public List<GiftPet> petsOfPost(long postId) {
        List<Long> petIds = jdbc.queryForList(POST_PET_SQL, Map.of("postId", postId), Long.class);
        if (petIds.isEmpty()) {
            return List.of();
        }
        return pet(petIds.get(0)).map(List::of).orElse(List.of());
    }
}
