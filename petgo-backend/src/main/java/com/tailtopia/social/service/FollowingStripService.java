package com.tailtopia.social.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.social.dto.FollowingStripResponse;
import com.tailtopia.social.repository.UserFollowRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关注头像排（V1.3.4 following 流）。排序 / 排除全在 {@link UserFollowRepository#findStripUserIds} 一条 SQL，
 * 展示字段（昵称 / 头像 / token）各批量取一次，不逐行查。
 */
@Service
public class FollowingStripService {

    /** 返回上限。 */
    static final int LIMIT = 20;

    private final UserFollowRepository follows;
    private final AccountQueryService accounts;

    public FollowingStripService(UserFollowRepository follows, AccountQueryService accounts) {
        this.follows = follows;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public FollowingStripResponse stripFor(long viewerId) {
        List<Long> ids = follows.findStripUserIds(viewerId, LIMIT);
        if (ids.isEmpty()) {
            return new FollowingStripResponse(List.of());
        }
        Map<Long, AuthorView> views = accounts.findAuthorViewsWithoutTags(ids);
        Map<Long, String> tokens = accounts.publicTokensOf(ids);
        // 查询与补字段之间对方恰好注销 / 封号 → 拿不到 token，丢掉这一行。
        List<FollowingStripResponse.Item> items = ids.stream()
                .filter(tokens::containsKey)
                .map(id -> {
                    AuthorView v = views.getOrDefault(id, AuthorView.anonymized(id));
                    return new FollowingStripResponse.Item(id, tokens.get(id), v.nickname(), v.avatarUrl(),
                            v.badgeLevel(), v.badgeTier(), v.badgeFrame());
                })
                .toList();
        return new FollowingStripResponse(items);
    }
}
