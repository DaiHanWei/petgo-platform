package com.tailtopia.social.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.auth.service.FollowCounterService;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.social.repository.OfficialFollowOptoutRepository;
import com.tailtopia.social.repository.UserFollowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新用户自动关注官方号（V1.3.4 following 流）。
 *
 * <p>仅对有效的 {@code role=USER} 执行；对每个有效 ADMIN 官方号：用户曾主动取关（optout）或已拉黑对方 → 跳过；
 * 否则以 {@code source=SYSTEM} 写入关注行，真正新建时同事务内两端计数 +1。
 *
 * <p>⚠️ 不发 {@code UserFollowedEvent}（官方号不应被注册刷屏通知），不走限流 / 每日上限。
 * 幂等：重复调用只会跳过已存在的行。
 */
@Service
public class OfficialAutoFollowService {

    private final AccountQueryService accounts;
    private final UserFollowRepository follows;
    private final OfficialFollowOptoutRepository optouts;
    private final FollowCounterService counters;
    private final UserHideRelationReader hideRelations;

    public OfficialAutoFollowService(AccountQueryService accounts, UserFollowRepository follows,
            OfficialFollowOptoutRepository optouts, FollowCounterService counters,
            UserHideRelationReader hideRelations) {
        this.accounts = accounts;
        this.follows = follows;
        this.optouts = optouts;
        this.counters = counters;
        this.hideRelations = hideRelations;
    }

    /**
     * REQUIRES_NEW：注册钩子在 AFTER_COMMIT 阶段调用，此时外层事务资源仍绑定线程，
     * 用 REQUIRED 会加入一个已提交的事务而静默不落库。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void followOfficialAccounts(long userId) {
        if (!accounts.isActiveRegularUser(userId)) {
            return;
        }
        for (long officialId : accounts.activeOfficialIds()) {
            if (officialId == userId
                    || optouts.exists(userId, officialId)
                    || hideRelations.isBlocked(userId, officialId)) {
                continue;
            }
            if (follows.insertSystemIfAbsent(userId, officialId) == 1) {
                counters.applyFollow(userId, officialId);
            }
        }
    }
}
