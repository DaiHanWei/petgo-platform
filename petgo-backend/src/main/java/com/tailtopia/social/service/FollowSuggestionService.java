package com.tailtopia.social.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.social.dto.FollowListItem;
import com.tailtopia.social.dto.FollowSuggestionsResponse;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.repository.UserFollowRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 推荐关注（V1.3.4 follow · Story 2.3 · AD-4 / FR-140.11）—— 我自己的关注 / 粉丝列表为空时展示。
 *
 * <p>候选资格、排除与排序全部在 {@link UserFollowRepository#findSuggestionIds} 一条 SQL 内完成；
 * 本服务只按其次序批量补展示字段（昵称 / 头像、token、followsMe 各一次，NFR3）。
 * 不做任何缓存（CLAUDE.md 护栏：不引通用缓存层）。
 */
@Service
public class FollowSuggestionService {

    /** 返回上限（AD-4）。 */
    static final int LIMIT = 30;

    /** 获赞统计窗口：近 30 天（FR-140.11）。 */
    static final Duration LIKE_WINDOW = Duration.ofDays(30);

    private final UserFollowRepository follows;
    private final AccountQueryService accounts;
    private final FollowReader followReader;
    private final PetProfileRepository pets;
    private final Clock clock;

    @Autowired
    public FollowSuggestionService(UserFollowRepository follows, AccountQueryService accounts,
            FollowReader followReader, PetProfileRepository pets) {
        this(follows, accounts, followReader, pets, Clock.systemUTC());
    }

    FollowSuggestionService(UserFollowRepository follows, AccountQueryService accounts,
            FollowReader followReader, PetProfileRepository pets, Clock clock) {
        this.follows = follows;
        this.accounts = accounts;
        this.followReader = followReader;
        this.pets = pets;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FollowSuggestionsResponse suggestionsFor(long viewerId) {
        List<Long> ids = follows.findSuggestionIds(viewerId, clock.instant().minus(LIKE_WINDOW), LIMIT);
        if (ids.isEmpty()) {
            return new FollowSuggestionsResponse(List.of());
        }
        Map<Long, AuthorView> views = accounts.findAuthorViewsWithoutTags(ids);
        Map<Long, String> tokens = accounts.publicTokensOf(ids);
        Map<Long, FollowState> states = followReader.followStates(viewerId, ids);
        // 宠物档案按 owner 批量一次查（V1 每人一只；pet_profiles 无软删列，物理删除）。
        Map<Long, PetProfile> petByOwner = new HashMap<>();
        for (PetProfile p : pets.findByOwnerIdIn(ids)) {
            petByOwner.putIfAbsent(p.getOwnerId(), p);
        }
        // 查询与补字段之间对方恰好注销 / 封号 → 拿不到 token，丢掉这一行（没有 token 就没法关注）。
        List<FollowListItem> items = ids.stream()
                .filter(tokens::containsKey)
                .map(id -> {
                    AuthorView v = views.getOrDefault(id, AuthorView.anonymized(id));
                    FollowState s = states.getOrDefault(id, FollowState.NONE);
                    PetProfile pet = petByOwner.get(id);
                    return new FollowListItem(id, tokens.get(id), v.nickname(), v.avatarUrl(),
                            false, false, s.followsMe(), false, false,
                            pet == null || pet.getPetType() == null ? null : pet.getPetType().name(),
                            pet == null ? null : pet.getBirthday());
                })
                .toList();
        return new FollowSuggestionsResponse(items);
    }
}
