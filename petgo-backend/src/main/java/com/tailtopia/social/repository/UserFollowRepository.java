package com.tailtopia.social.repository;

import com.tailtopia.social.domain.UserFollow;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 关注关系读写（V1.3.4 follow · Story 1.1）。
 *
 * <p>⚠️ <b>本仓储只允许 {@code com.tailtopia.social} 模块内部引用</b>；
 * {@code auth} / {@code content} / {@code notify} 一律经 {@code social.read.FollowReader}（同 AD-8 模块边界）。
 */
public interface UserFollowRepository extends JpaRepository<UserFollow, UserFollow.Key> {

    /**
     * 幂等关注：已关注则什么都不做（不刷新时间戳），返回实际插入行数（0 / 1）。
     *
     * <p>⚠️ 必须用 {@code ON CONFLICT DO NOTHING}，不要改成「save + catch 唯一约束异常」——
     * 约束异常穿出 repo 代理时共享事务已被标记 rollback-only，外层提交必 500（并发双击就能触发）。
     * 调用方<b>只在返回 1 时</b>改计数，并发双击只加一次。
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_follows (follower_id, followee_id, created_at)
            VALUES (:followerId, :followeeId, now())
            ON CONFLICT (follower_id, followee_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("followerId") long followerId, @Param("followeeId") long followeeId);

    /** 取关：返回实际删除行数（0 / 1）。调用方只在返回 1 时改计数。 */
    @Modifying
    @Query(value = "DELETE FROM user_follows WHERE follower_id = :followerId AND followee_id = :followeeId",
            nativeQuery = true)
    int deleteRelation(@Param("followerId") long followerId, @Param("followeeId") long followeeId);

    boolean existsByFollowerIdAndFolloweeId(long followerId, long followeeId);

    /**
     * 某人自 {@code since} 起新建、<b>且仍存在</b>的关注行数（每日新增上限，AD-4 / T-3）。
     * 走 {@code idx_user_follows_follower_created}。
     */
    @Query(value = "SELECT count(*) FROM user_follows WHERE follower_id = :followerId AND created_at >= :since",
            nativeQuery = true)
    long countCreatedSince(@Param("followerId") long followerId, @Param("since") Instant since);

    /**
     * viewer 与这批人之间<b>两个方向</b>的全部关注行 —— 一条 SQL 解决整批（AD-16，禁止逐个 exists）。
     * 调用方保证 {@code targetIds} 非空（{@code IN ()} 在 Postgres 上是语法错误）。
     */
    @Query("SELECT f FROM UserFollow f "
            + "WHERE (f.followerId = :viewerId AND f.followeeId IN :targetIds) "
            + "   OR (f.followeeId = :viewerId AND f.followerId IN :targetIds)")
    List<UserFollow> findBetween(@Param("viewerId") long viewerId,
            @Param("targetIds") List<Long> targetIds);

    /**
     * 关注列表一页（Story 2.2 · AD-4）：{@code ownerId} 关注的人，keyset {@code created_at DESC, followee_id DESC}。
     * 调用方传 {@code limit + 1} 判 hasMore；首页用 {@code KeysetCursor.firstPage()} 哨兵（不需要 hasCursor 门控）。
     * 走 {@code idx_user_follows_follower_created}（T-4 三列）。
     */
    @Query("SELECT f FROM UserFollow f WHERE f.followerId = :ownerId "
            + "AND (f.createdAt < :cursorTs OR (f.createdAt = :cursorTs AND f.followeeId < :cursorId)) "
            + "ORDER BY f.createdAt DESC, f.followeeId DESC")
    List<UserFollow> findFollowingPage(@Param("ownerId") long ownerId,
            @Param("cursorTs") Instant cursorTs, @Param("cursorId") long cursorId,
            org.springframework.data.domain.Pageable page);

    /** 粉丝列表一页：关注 {@code ownerId} 的人，keyset {@code created_at DESC, follower_id DESC}。 */
    @Query("SELECT f FROM UserFollow f WHERE f.followeeId = :ownerId "
            + "AND (f.createdAt < :cursorTs OR (f.createdAt = :cursorTs AND f.followerId < :cursorId)) "
            + "ORDER BY f.createdAt DESC, f.followerId DESC")
    List<UserFollow> findFollowersPage(@Param("ownerId") long ownerId,
            @Param("cursorTs") Instant cursorTs, @Param("cursorId") long cursorId,
            org.springframework.data.domain.Pageable page);

    /** 注销清理：删除该用户两个方向的全部关注行，返回删除行数。 */
    @Modifying
    @Query(value = "DELETE FROM user_follows WHERE follower_id = :userId OR followee_id = :userId",
            nativeQuery = true)
    int deleteAllInvolving(@Param("userId") long userId);
}
