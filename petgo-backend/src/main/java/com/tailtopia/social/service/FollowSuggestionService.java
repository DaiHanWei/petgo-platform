package com.tailtopia.social.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.social.dto.FollowListItem;
import com.tailtopia.social.dto.FollowSuggestionsResponse;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.repository.UserFollowRepository;
import java.time.Clock;
import java.time.Duration;
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
    private final Clock clock;

    @Autowired
    public FollowSuggestionService(UserFollowRepository follows, AccountQueryService accounts,
            FollowReader followReader) {
        this(follows, accounts, followReader, Clock.systemUTC());
    }

    /** 可注入时钟（L1 测试用固定时钟隔离共享库里其他测试留下的点赞）。 */
    public FollowSuggestionService(UserFollowRepository follows, AccountQueryService accounts,
            FollowReader followReader, Clock clock) {
        this.follows = follows;
        this.accounts = accounts;
        this.followReader = followReader;
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
        // 查询与补字段之间对方恰好注销 / 封号 → 拿不到 token，丢掉这一行（没有 token 就没法关注）。
        List<FollowListItem> items = ids.stream()
                .filter(tokens::containsKey)
                .map(id -> {
                    AuthorView v = views.getOrDefault(id, AuthorView.anonymized(id));
                    FollowState s = states.getOrDefault(id, FollowState.NONE);
                    return new FollowListItem(id, tokens.get(id), v.nickname(), v.avatarUrl(),
                            false, false, s.followsMe(), false, false);
                })
                .toList();
        return new FollowSuggestionsResponse(items);
    }
}
