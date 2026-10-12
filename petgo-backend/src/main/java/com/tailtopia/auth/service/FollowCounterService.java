package com.tailtopia.auth.service;

import com.tailtopia.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关注冗余计数的唯一写入口（V1.3.4 follow · Story 1.1 · AC6 / AD-1）。
 *
 * <p>两计数列属 {@code users} 表（auth 模块）；{@code social} 只调本服务，不直写 {@code users}。
 *
 * <p>🔴 {@code MANDATORY}：计数必须与关注行的写入<b>同一事务</b>（关注行插入成功才 +1），
 * 单独调用直接抛异常，杜绝「行写了、计数没跟上」。全部走 native 条件 UPDATE，
 * {@code GREATEST(…, 0)} 兜底；禁止读出来 ±1 再 save（并发丢更新）。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class FollowCounterService {

    private final UserRepository users;

    public FollowCounterService(UserRepository users) {
        this.users = users;
    }

    /** follower 新关注了 followee：follower 的 following +1、followee 的 follower +1。 */
    public void applyFollow(long followerId, long followeeId) {
        users.addFollowingCount(followerId, 1);
        users.addFollowerCount(followeeId, 1);
    }

    /** follower 取关了 followee：两端各 −1（不低于 0）。 */
    public void applyUnfollow(long followerId, long followeeId) {
        users.addFollowingCount(followerId, -1);
        users.addFollowerCount(followeeId, -1);
    }

    /**
     * 注销清理的计数部分（AC7）：他关注的每个人 follower −1、关注他的每个人 following −1、本人两计数置 0。
     *
     * <p>⚠️ 必须在删除关注行<b>之前</b>调用（扣减靠 JOIN 关注行定位对端）。
     */
    public void purgeCountsFor(long userId) {
        users.decrementFollowerCountsOfFollowees(userId);
        users.decrementFollowingCountsOfFollowers(userId);
        users.zeroFollowCounts(userId);
    }

    /** 重新读出最新的 {@code follower_count}（实体上的值是加载时快照）。 */
    public long followerCountOf(long userId) {
        Integer v = users.findFollowerCount(userId);
        return v == null ? 0L : v;
    }
}
