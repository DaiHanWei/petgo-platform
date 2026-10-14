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
     * 关注列表一页（Story 2.2 · AC1）：{@code ownerId} 关注的人，keyset {@code created_at DESC, followee_id DESC}。
     * 调用方传 {@code limit + 1} 判 hasMore；首页用 {@code KeysetCursor.firstPage()} 哨兵（不需要 hasCursor 门控）。
     *
     * <p>2.2 code-review F7：游标条件用<b>行值比较</b> {@code (created_at, followee_id) < (ts, id)}，
     * 不写 {@code created_at < ts OR (created_at = ts AND …)} —— 后者 Postgres 只能从该用户索引开头扫起、
     * 边扫边过滤，翻页越深越慢；行值比较能直接在 {@code idx_user_follows_follower_created}（T-4 三列）上定位起点。
     * 用 native 写，避免依赖 HQL 元组比较的方言渲染。
     */
    @Query(value = """
            SELECT * FROM user_follows
             WHERE follower_id = :ownerId AND (created_at, followee_id) < (:cursorTs, :cursorId)
             ORDER BY created_at DESC, followee_id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<UserFollow> findFollowingPage(@Param("ownerId") long ownerId,
            @Param("cursorTs") Instant cursorTs, @Param("cursorId") long cursorId, @Param("limit") int limit);

    /** 粉丝列表一页：关注 {@code ownerId} 的人，keyset {@code created_at DESC, follower_id DESC}（行值比较，同上）。 */
    @Query(value = """
            SELECT * FROM user_follows
             WHERE followee_id = :ownerId AND (created_at, follower_id) < (:cursorTs, :cursorId)
             ORDER BY created_at DESC, follower_id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<UserFollow> findFollowersPage(@Param("ownerId") long ownerId,
            @Param("cursorTs") Instant cursorTs, @Param("cursorId") long cursorId, @Param("limit") int limit);

    /**
     * 推荐关注候选 id，已按推荐次序排好（Story 2.3 · AD-4 / FR-140.11）。
     *
     * <p>⚠️ <b>有意例外</b>：本查询直接读 {@code users} / {@code content_posts} / {@code content_likes} /
     * {@code user_hide_relations}，打破「social 不 join users」。理由同 {@code ContentPostRepository} Feed 查询：
     * 接口硬上限 30 且要求返回的都有效，资格与排除过滤必须在 SQL WHERE 内完成——取 30 条后在 Java 侧
     * 剔除会掉到 30 以下。展示字段（昵称 / 头像 / token）仍经 {@code AccountQueryService} 批量取。
     *
     * <ul>
     *   <li>资格：{@code role} 为 USER 或 ADMIN（官方内容账号也进推荐，2026-10-13 决定；兽医不进）、未注销、
     *       {@code enabled}、{@code status=ACTIVE}（封号排除，D-8 / T-26），
     *       且至少一条 PUBLIC + PUBLISHED + 未软删的帖；虚拟号与真人都包括。</li>
     *   <li>排除：自己、viewer 已关注的人、viewer 作为 holder 有<b>任意来源</b>隐藏关系的人（单向，T-26）。</li>
     *   <li>排序：{@code since} 起在其 PUBLIC + PUBLISHED + 未软删帖上的点赞数倒序（口径同主页「Suka」，T-27），
     *       并列按最近一条公开帖时间倒序、再按 id 倒序（T-22）。</li>
     * </ul>
     */
    @Query(value = """
            WITH publishers AS (
                SELECT p.author_id, MAX(p.created_at) AS last_public_at
                  FROM content_posts p
                 WHERE p.visibility = 'PUBLIC' AND p.status = 'PUBLISHED' AND p.deleted_at IS NULL
                 GROUP BY p.author_id
            ),
            recent_likes AS (
                SELECT p.author_id, COUNT(*) AS likes_30d
                  FROM content_likes l
                  JOIN content_posts p ON p.id = l.post_id
                 WHERE l.created_at >= :since
                   AND p.visibility = 'PUBLIC' AND p.status = 'PUBLISHED' AND p.deleted_at IS NULL
                 GROUP BY p.author_id
            )
            SELECT u.id
              FROM publishers pb
              JOIN users u ON u.id = pb.author_id
              LEFT JOIN recent_likes r ON r.author_id = pb.author_id
             WHERE u.role IN ('USER', 'ADMIN') AND u.deleted_at IS NULL AND u.enabled = true AND u.status = 'ACTIVE'
               AND u.id <> :viewerId
               AND NOT EXISTS (SELECT 1 FROM user_follows f
                                WHERE f.follower_id = :viewerId AND f.followee_id = u.id)
               AND NOT EXISTS (SELECT 1 FROM user_hide_relations h
                                WHERE h.holder_id = :viewerId AND h.target_id = u.id)
             ORDER BY COALESCE(r.likes_30d, 0) DESC, pb.last_public_at DESC, u.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findSuggestionIds(@Param("viewerId") long viewerId, @Param("since") Instant since,
            @Param("limit") int limit);

    /** 注销清理：删除该用户两个方向的全部关注行，返回删除行数。 */
    @Modifying
    @Query(value = "DELETE FROM user_follows WHERE follower_id = :userId OR followee_id = :userId",
            nativeQuery = true)
    int deleteAllInvolving(@Param("userId") long userId);
}
