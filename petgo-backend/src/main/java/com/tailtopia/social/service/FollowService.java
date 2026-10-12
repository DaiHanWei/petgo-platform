package com.tailtopia.social.service;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.auth.service.FollowCounterService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.social.dto.FollowResultResponse;
import com.tailtopia.social.event.UserFollowedEvent;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.social.repository.UserFollowRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关注 / 取关（V1.3.4 follow · Story 1.1 · FR-140 / AD-1 / AD-4）。
 *
 * <p><b>拒绝顺序</b>（关注）：限流 → 目标不存在 / 已注销 / 封号 404 → 自己 422 →
 * 我拉黑了对方 409 {@code follow-blocked} → 已关注直接返回（幂等，不计每日上限）→ 每日上限 429 → 写入。
 * <b>对方拉黑了我照常成功</b>（D-4，不暴露被拉黑）；{@code REPORT} 来源不拦。
 *
 * <p><b>计数</b>：关注行 {@code INSERT … ON CONFLICT DO NOTHING} / {@code DELETE} 的受影响行数 = 1 时，
 * 才在同一事务内经 {@link FollowCounterService} 条件 ±1 —— 并发双击只算一次。
 *
 * <p>🛡 日志只记内部 id，<b>不记 token</b>（token 是对外标识）。
 *
 * <p>Story 3.2：关注行<b>真正新建</b>时发布 {@link UserFollowedEvent}（与计数 +1 同一分支，事务内发布、
 * 通知监听器 AFTER_COMMIT 才执行）；其余路径（幂等、拒绝、取关、拉黑联动、注销清理）一律不发。
 * <b>不在这里判接收方隐藏关系</b>（D-4 / NFR2：被拉黑方从关注结果里察觉不到），抑制在通知侧。
 */
@Service
public class FollowService {

    /** 关注与取关共用的每分钟限流桶（T-1）。 */
    static final int PER_MINUTE_LIMIT = 30;

    /** 每日新增关注上限（WIB 自然日，AD-4 / T-3）。 */
    static final int DAILY_LIMIT = 500;

    /** 「每日」的日界：雅加达自然日（与仓库其他每日上限同口径）。 */
    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final UserFollowRepository follows;
    private final FollowCounterService counters;
    private final AccountQueryService accounts;
    private final UserHideRelationReader hideRelations;
    private final FollowReader followReader;
    private final RedisRateLimiter rateLimiter;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public FollowService(UserFollowRepository follows, FollowCounterService counters,
            AccountQueryService accounts, UserHideRelationReader hideRelations,
            FollowReader followReader, RedisRateLimiter rateLimiter, ApplicationEventPublisher events) {
        this(follows, counters, accounts, hideRelations, followReader, rateLimiter, events, Clock.systemUTC());
    }

    FollowService(UserFollowRepository follows, FollowCounterService counters,
            AccountQueryService accounts, UserHideRelationReader hideRelations,
            FollowReader followReader, RedisRateLimiter rateLimiter, ApplicationEventPublisher events,
            Clock clock) {
        this.follows = follows;
        this.counters = counters;
        this.accounts = accounts;
        this.hideRelations = hideRelations;
        this.followReader = followReader;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.clock = clock;
    }

    /** 关注（幂等）。 */
    @Transactional
    public FollowResultResponse follow(long viewerId, String targetToken) {
        checkRate(viewerId);
        // 🔴 调用方本人必须仍有效（未注销、未封号）：注销只吊销 refresh token，access token 还能用到过期；
        // 注销清理（purgeUser）跑完后再关注，会留下一条再也没有清理入口的脏关注行（code-review 2026-10-12）。
        if (!accounts.isActive(viewerId)) {
            throw AppException.unauthorized("登录已失效，请重新登录");
        }
        // 不存在 / 已注销 / 封号 → 404（D-8）；不按 role 过滤（D-9 官方号可被关注）；不看 enabled（T-2）。
        User target = accounts.findActiveByPublicToken(targetToken)
                .orElseThrow(() -> AppException.notFound("用户不存在"));
        long targetId = target.getId();
        requireNotSelf(viewerId, targetId);
        // ⚠️ 只认 BLOCK（我拉黑了对方）；对方拉黑我照常往下走（D-4），举报隐藏不拦。
        if (hideRelations.isBlocked(viewerId, targetId)) {
            throw AppException.followBlocked("你已拉黑该用户");
        }
        if (!follows.existsByFollowerIdAndFolloweeId(viewerId, targetId)) {
            checkDailyLimit(viewerId);
            if (follows.insertIfAbsent(viewerId, targetId) == 1) {
                counters.applyFollow(viewerId, targetId);
                // Story 3.2：只在真正新建时发（并发双击只发一次）；对方拉黑了我也照发，抑制在通知侧（D-4）。
                events.publishEvent(new UserFollowedEvent(viewerId, targetId, clock.instant()));
            }
        }
        return result(viewerId, targetId, true);
    }

    /**
     * 取关（幂等，未关注时静默成功）。<b>不发任何通知或推送</b>（FR-140.12 / PRD AC9）。
     *
     * <p>⚠️ 目标解析用 {@code findByPublicToken}（<b>不看封号</b>，D-8）：封号用户仍留在我的关注列表里，
     * 若这里也要求对方 ACTIVE，用户就永远取关不掉他。不存在 / 已注销仍 404。
     */
    @Transactional
    public FollowResultResponse unfollow(long viewerId, String targetToken) {
        checkRate(viewerId);
        User target = accounts.findByPublicToken(targetToken)
                .orElseThrow(() -> AppException.notFound("用户不存在"));
        long targetId = target.getId();
        requireNotSelf(viewerId, targetId);
        if (follows.deleteRelation(viewerId, targetId) == 1) {
            counters.applyUnfollow(viewerId, targetId);
        }
        return result(viewerId, targetId, false);
    }

    /**
     * 拉黑联动取关（V1.3.4 follow Story 1.2 · AD-5 / FR-140.13）：删除 {@code holder → target} 的关注行，
     * <b>仅当删除行数 = 1</b> 时两端计数 −1。由 {@code UserHideRelationService.block} 在插入 BLOCK 行的
     * <b>同一事务</b>里调用（REQUIRED 加入外层事务，任一步失败整体回滚）。
     *
     * <p>⚠️ {@code target → holder} 保留不动；<b>不限流、不发任何通知 / 推送 / 事件</b>（PRD AC6 / AC9）；
     * 重复拉黑删 0 行即无副作用；解除拉黑不恢复。
     */
    @Transactional
    public void removeOnBlock(long holderId, long targetId) {
        if (follows.deleteRelation(holderId, targetId) == 1) {
            counters.applyUnfollow(holderId, targetId);
        }
    }

    /**
     * 注销清理关注关系（AC7，幂等可重跑）：先扣对端计数（他关注的人 follower −1、关注他的人 following −1），
     * 再删两个方向的全部行，本人计数置 0。
     *
     * <p>⚠️ <b>不能依赖 FK 的 ON DELETE CASCADE</b>：注销是就地匿名化，{@code users} 行不删，CASCADE 永远不触发。
     * 封号不调用本方法（不删关系、不改计数，AD-1）。
     */
    @Transactional
    public void purgeUser(long userId) {
        counters.purgeCountsFor(userId);
        follows.deleteAllInvolving(userId);
    }

    private FollowResultResponse result(long viewerId, long targetId, boolean iFollow) {
        boolean followsMe = followReader.followState(viewerId, targetId).followsMe();
        return new FollowResultResponse(iFollow, followsMe, counters.followerCountOf(targetId));
    }

    private void checkRate(long viewerId) {
        rateLimiter.check("rl:social:follow:" + viewerId, PER_MINUTE_LIMIT, Duration.ofMinutes(1));
    }

    /** 当日（WIB 自然日）新建且仍存在的关注行数 ≥ 500 → 429（AD-4 / T-3）。 */
    private void checkDailyLimit(long viewerId) {
        Instant dayStart = clock.instant().atZone(WIB).toLocalDate().atStartOfDay(WIB).toInstant();
        if (follows.countCreatedSince(viewerId, dayStart) >= DAILY_LIMIT) {
            throw AppException.rateLimited("今日关注次数已达上限");
        }
    }

    private static void requireNotSelf(long viewerId, long targetId) {
        if (viewerId == targetId) {
            throw AppException.validation("不能关注自己");
        }
    }
}
