package com.tailtopia.social.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.auth.service.FollowCounterService;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.social.repository.AutoFollowBackfillDao;
import com.tailtopia.social.repository.OfficialFollowOptoutRepository;
import com.tailtopia.social.repository.UserFollowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新用户自动关注（V1.3.4 following 流；V1.4.1 起目标改为配置指定的账号，见 {@link AutoFollowTargets}）。
 *
 * <p>仅对有效的 {@code role=USER} 执行；对每个有效的目标账号：用户曾主动取关（optout）或已拉黑对方 → 跳过；
 * 否则以 {@code source=SYSTEM} 写入关注行，真正新建时同事务内两端计数 +1。
 * 老用户的一次性补关注见 {@link #backfill}。
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
    private final AutoFollowTargets targets;
    private final AutoFollowBackfillDao backfillDao;

    public OfficialAutoFollowService(AccountQueryService accounts, UserFollowRepository follows,
            OfficialFollowOptoutRepository optouts, FollowCounterService counters,
            UserHideRelationReader hideRelations, AutoFollowTargets targets, AutoFollowBackfillDao backfillDao) {
        this.accounts = accounts;
        this.follows = follows;
        this.optouts = optouts;
        this.counters = counters;
        this.hideRelations = hideRelations;
        this.targets = targets;
        this.backfillDao = backfillDao;
    }

    /**
     * REQUIRES_NEW：注册钩子在 AFTER_COMMIT 阶段调用，此时外层事务资源仍绑定线程，
     * 用 REQUIRED 会加入一个已提交的事务而静默不落库。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void followOfficialAccounts(long userId) {
        followAccounts(userId, targets.ids());
    }

    /** 同 {@link #followOfficialAccounts}，目标显式给出（测试与运维用；失效账号同样跳过）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void followAccounts(long userId, java.util.List<Long> targetIds) {
        if (!accounts.isActiveRegularUser(userId)) {
            return;
        }
        for (long officialId : activeOf(targetIds)) {
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

    /**
     * 老用户一次性补关注（V1.4.1，「老用户也默认全部关注」）：让所有有效真实用户关注每个目标账号。
     * 规则与 {@link #followOfficialAccounts} 一致（跳过 optout / 已拉黑 / 已关注 / 本人），整批一条 SQL。
     * 幂等：重复执行只补漏掉的人。返回每个目标新增的关注数。
     */
    @Transactional
    public java.util.Map<Long, Integer> backfill() {
        return backfill(targets.ids());
    }

    /** 同 {@link #backfill()}，目标显式给出（测试与运维用）。 */
    @Transactional
    public java.util.Map<Long, Integer> backfill(java.util.List<Long> targetIds) {
        java.util.Map<Long, Integer> added = new java.util.LinkedHashMap<>();
        for (long targetId : activeOf(targetIds)) {
            added.put(targetId, backfillDao.backfill(targetId));
        }
        return added;
    }

    /** 目标里仍有效（存在、未注销、未封号）的账号。失效的跳过，不报错（运营可能注销了旧号）。 */
    private java.util.List<Long> activeOf(java.util.List<Long> targetIds) {
        return targetIds.stream().filter(accounts::isActiveAccount).toList();
    }
}
