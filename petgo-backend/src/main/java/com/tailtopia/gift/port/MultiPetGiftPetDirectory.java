package com.tailtopia.gift.port;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentPetQuery;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.PetView;
import com.tailtopia.profile.service.PetQueryService;
import com.tailtopia.profile.service.ProfileService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link GiftPetDirectory} 的多宠实现（V1.4.0 gift Story 4.1 · AC1 · AD-5 / §6.2），取代 1.1 的单宠过渡实现。
 *
 * <ul>
 *   <li>宠物：② 的 {@link PetQueryService}（<b>不校验归属、不校验只读</b> —— 只读宠物可收礼，PRD §6.3 注）；
 *       品种 {@code PetView} 不含，取 ② 已有的按 id 取档案读口 {@link ProfileService#findById}（同一事务内
 *       一级缓存命中，不多发查询）。</li>
 *   <li>主人状态：账号读口 {@link AccountQueryService#findAccountById} 一次取，口径与 1.1 过渡实现逐字相同 ——
 *       {@code ownerActive} = 未注销且 {@code status = ACTIVE}（同 {@code AccountQueryService.isActive}），
 *       {@code ownerDeleted} = {@code users.deleted_at IS NOT NULL}；主人行不存在 → 视为宠物不存在。</li>
 *   <li>帖子关联宠物：先判帖子可送礼（② 的 {@link ContentPetQuery} 明确不判可见性；content 模块没有同口径读口，
 *       保留 1.1 那条<b>只查 {@code content_posts} 状态列</b>的 SQL，守卫单独放行本类），再按
 *       {@link ContentPetQuery#petsOf} 的顺序（宠物创建顺序，与详情 {@code pets} 同一口径）批量取，取不到的过滤掉。</li>
 * </ul>
 *
 * <p>🔴 送礼包内只有本类允许引用 ② 的读口与 {@code content_posts}（{@code GiftModuleBoundaryGuardTest} 钉着）。
 */
@Component
public class MultiPetGiftPetDirectory implements GiftPetDirectory {

    /** 帖子可送礼：公开、已发布、未举报下架、未删除的 Diary（GROWTH_MOMENT）。只读状态列，不读宠物列。 */
    static final String GIFTABLE_POST_SQL = """
            SELECT EXISTS (
                SELECT 1
                  FROM content_posts
                 WHERE id = :postId
                   AND type = 'GROWTH_MOMENT'
                   AND deleted_at IS NULL
                   AND status = 'PUBLISHED'
                   AND visibility = 'PUBLIC'
                   AND report_hidden_at IS NULL)
            """;

    private final PetQueryService petQuery;
    private final ProfileService profiles;
    private final ContentPetQuery contentPets;
    private final AccountQueryService accounts;
    private final NamedParameterJdbcTemplate jdbc;

    public MultiPetGiftPetDirectory(PetQueryService petQuery, ProfileService profiles, ContentPetQuery contentPets,
            AccountQueryService accounts, NamedParameterJdbcTemplate jdbc) {
        this.petQuery = petQuery;
        this.profiles = profiles;
        this.contentPets = contentPets;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GiftPet> pet(long petId) {
        return petQuery.get(petId).flatMap(v -> toGiftPet(v, new HashMap<>()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<GiftPet> petsOfPost(long postId) {
        Boolean giftable = jdbc.queryForObject(GIFTABLE_POST_SQL, Map.of("postId", postId), Boolean.class);
        if (!Boolean.TRUE.equals(giftable)) {
            return List.of();
        }
        List<Long> ids = contentPets.petsOf(postId);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, PetView> views = petQuery.getAll(ids);
        Map<Long, Optional<User>> owners = new HashMap<>();
        List<GiftPet> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            PetView v = views.get(id);
            if (v != null) {
                toGiftPet(v, owners).ifPresent(out::add);
            }
        }
        return List.copyOf(out);
    }

    private Optional<GiftPet> toGiftPet(PetView v, Map<Long, Optional<User>> ownerCache) {
        Optional<User> owner = ownerCache.computeIfAbsent(v.ownerId(), accounts::findAccountById);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        User u = owner.get();
        boolean deleted = u.getDeletedAt() != null;
        String breed = profiles.findById(v.id()).map(PetProfile::getBreed).orElse(null);
        return Optional.of(new GiftPet(v.id(), v.ownerId(), v.name(), v.avatarUrl(),
                v.petType() == null ? null : v.petType().name(), breed,
                !deleted && u.getStatus() == UserStatus.ACTIVE, deleted));
    }
}
