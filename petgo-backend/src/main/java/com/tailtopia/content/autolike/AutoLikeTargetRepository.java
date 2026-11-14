package com.tailtopia.content.autolike;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 自动点赞目标集读写（V1.5.0 Story 2.1）。写入一律原生 SQL（ON CONFLICT / 集合 UPDATE），调用方持有事务。 */
public interface AutoLikeTargetRepository extends JpaRepository<AutoLikeTarget, Long> {

    Optional<AutoLikeTarget> findByPostId(long postId);

    /** 表里是否有任何一行（含已离开的）。为空 = 上线首轮（FR-221），只看这一条。 */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM auto_like_targets)", nativeQuery = true)
    boolean existsAny();

    /** 关闭超龄：仍在集合、发帖时间 {@code <= cutoff}（= 本轮开始 − 最长年龄）→ {@code MAX_AGE}。 */
    @Modifying
    @Query(value = """
            UPDATE auto_like_targets SET left_at = :runStart, leave_reason = 'MAX_AGE', updated_at = now()
            WHERE joined AND left_at IS NULL AND post_created_at <= :cutoff
            """, nativeQuery = true)
    int closeMaxAge(@Param("runStart") Instant runStart, @Param("cutoff") Instant cutoff);

    /** 关闭失败过多：仍在集合、失败次数达到上限 → {@code TOO_MANY_FAILURES}（在下一轮计划开始时执行，Q-4）。 */
    @Modifying
    @Query(value = """
            UPDATE auto_like_targets SET left_at = :runStart, leave_reason = 'TOO_MANY_FAILURES', updated_at = now()
            WHERE joined AND left_at IS NULL AND failed_attempts >= :maxFailures
            """, nativeQuery = true)
    int closeTooManyFailures(@Param("runStart") Instant runStart, @Param("maxFailures") int maxFailures);

    /**
     * 本轮待检查的帖子（FR-213，架构 L-8 第 5 步）：真人帖（PUBLISHED + PUBLIC + 未删；作者 REAL + USER + 未注销 +
     * ACTIVE，Q-8），发帖时间在 {@code (maxCut, minCut]}，尚无目标集行；最老优先。
     * {@code like_count} = 该帖<b>全部</b>赞（真人 + 虚拟 + 自动，即 App 显示的数）。
     */
    @Query(value = """
            SELECT p.id, p.author_id, p.created_at,
                   (SELECT COUNT(*) FROM content_likes l WHERE l.post_id = p.id) AS like_count
            FROM content_posts p JOIN users u ON u.id = p.author_id
            WHERE p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.deleted_at IS NULL
              AND u.account_type = 'REAL' AND u.role = 'USER' AND u.deleted_at IS NULL
              AND u.status = 'ACTIVE'
              AND p.created_at <= :minCut
              AND p.created_at > :maxCut
              AND NOT EXISTS (SELECT 1 FROM auto_like_targets t WHERE t.post_id = p.id)
            ORDER BY p.created_at, p.id
            """, nativeQuery = true)
    List<Object[]> findCheckCandidateRows(@Param("minCut") Instant minCut, @Param("maxCut") Instant maxCut);

    /** {@link #findCheckCandidateRows} 的类型化版本（原生查询的 timestamptz 可能回 OffsetDateTime / Timestamp，统一转 Instant）。 */
    default List<CheckCandidate> findCheckCandidates(Instant minCut, Instant maxCut) {
        return findCheckCandidateRows(minCut, maxCut).stream()
                .map(r -> new CheckCandidate(((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
                        AutoLikeSql.toInstant(r[2]), ((Number) r[3]).intValue()))
                .toList();
    }

    /**
     * 写一条检查记录；该帖已有行则不写（返回 0）。
     *
     * <p>{@code leaveReason} 为空串 = 进集合（{@code joined = true}，{@code left_at / leave_reason} 写 NULL）；
     * 非空 = 不进集合，{@code left_at = checked_at}。不绑定 null 参数（原生查询绑 null 在 PG 上类型推断不可靠）。
     */
    @Modifying
    @Query(value = """
            INSERT INTO auto_like_targets (post_id, author_id, post_created_at, checked_at, like_count_at_check,
                                           joined, left_at, leave_reason)
            VALUES (:postId, :authorId, :postCreatedAt, :checkedAt, :likeCount,
                    (:leaveReason = ''),
                    CASE WHEN :leaveReason = '' THEN NULL ELSE CAST(:checkedAt AS timestamptz) END,
                    NULLIF(:leaveReason, ''))
            ON CONFLICT (post_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("postId") long postId, @Param("authorId") long authorId,
            @Param("postCreatedAt") Instant postCreatedAt, @Param("checkedAt") Instant checkedAt,
            @Param("likeCount") int likeCount, @Param("leaveReason") String leaveReason);

    /** 写一条检查记录：{@code reason == null} = 进集合，否则 = 不进集合的原因。该帖已有行则不写（返回 0）。 */
    default int insertCheck(CheckCandidate c, Instant checkedAt, AutoLikeLeaveReason reason) {
        return insertIfAbsent(c.postId(), c.authorId(), c.createdAt(), checkedAt, c.likeCount(),
                reason == null ? "" : reason.name());
    }

    /** 检查候选：{@code likeCount} 为展示口径（真人 + 虚拟 + 自动）。 */
    record CheckCandidate(long postId, long authorId, Instant createdAt, int likeCount) {
    }
}
