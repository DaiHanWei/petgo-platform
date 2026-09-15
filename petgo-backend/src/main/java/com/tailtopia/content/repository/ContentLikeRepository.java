package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentLike;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 点赞读写（Story 3.4）。计数实时 {@code COUNT(*)}（V1 不上缓存）；唯一约束兜底防重。
 */
public interface ContentLikeRepository extends JpaRepository<ContentLike, Long> {

    boolean existsByPostIdAndUserId(long postId, long userId);

    long countByPostId(long postId);

    /**
     * 某作者**全部 PUBLIC 内容的获赞总和**（V1.3.0 batch-b1 Story 2.2 · FR-118.2 · AC2/AC3）。
     *
     * <p>🔴 <b>一条 SQL 出数，绝不逐帖 count 再相加</b> —— 后者在一个发过 200 条的人身上
     * 就是 200 次查询（AD-6 / NFR-6）。
     *
     * <p>🛡 <b>也绝不为这个数新增冗余计数列</b>（AD-6 明令）。Story 1.8 的场所推荐/不推荐
     * 走 Redis 计数器是<b>经明确拍板的例外，仅限那一处，不构成先例</b>。
     *
     * <p>⚠️ 口径与主页网格<b>必须同源</b>：只数 PUBLIC + PUBLISHED + 未软删的帖。
     * 否则页面上那句「342 suka」里混着私密内容的赞，而那个差值又是一条可推断的私密信息。
     *
     * @return 没有任何赞时为 0（{@code COUNT} 恒有一行，不会是 null）
     */
    @Query("""
            SELECT COUNT(l) FROM ContentLike l
            WHERE l.postId IN (
                SELECT p.id FROM ContentPost p
                WHERE p.authorId = :authorId
                  AND p.deletedAt IS NULL
                  AND p.status = com.tailtopia.content.domain.PostStatus.PUBLISHED
                  AND p.visibility = com.tailtopia.content.domain.ContentVisibility.PUBLIC
            )
            """)
    long sumLikesOnPublicPostsByAuthor(@Param("authorId") long authorId);

    /** 批量点赞数（Feed 卡片 likeCount，PRD-642）：一次 GROUP BY 取一页帖子的赞数，避免 N+1。 */
    @Query("SELECT l.postId AS postId, COUNT(l) AS likeCount FROM ContentLike l "
            + "WHERE l.postId IN :postIds GROUP BY l.postId")
    List<PostLikeCount> countByPostIdIn(@Param("postIds") Collection<Long> postIds);

    /**
     * 某时间窗内**产生的**点赞数，按窗口内赞数从多到少（2026-08-28，取代互动积分页的口径B）。
     *
     * <p>🔴 与 {@link #countByPostIdIn} 回答的是**两个不同的问题**：
     * 那个问「这些帖子至今一共多少赞」，本方法问「这段时间里产生了多少赞」。
     * 一条三个月前的帖子这周被翻出来点了 50 个赞，只有本方法看得见。
     * 运营做周报要的是后者，而按发布时间筛内容永远给不出这个数。
     *
     * <p>⚠️ 只返回**窗口内有赞**的帖子 —— 零赞的不在结果里（GROUP BY 不给空组造行）。
     * 这正是这一档口径想要的行数：没产生互动的内容不该占周报的位置。
     */
    @Query("SELECT l.postId AS postId, COUNT(l) AS likeCount FROM ContentLike l "
            + "WHERE l.createdAt >= :from AND l.createdAt < :to "
            + "GROUP BY l.postId ORDER BY COUNT(l) DESC, l.postId DESC")
    List<PostLikeCount> countInWindow(@Param("from") Instant from, @Param("to") Instant to,
            org.springframework.data.domain.Pageable pageable);

    /** 批量点赞数投影（postId → 赞数）。无赞的帖不在结果中，调用方默认 0。 */
    interface PostLikeCount {
        Long getPostId();

        long getLikeCount();
    }

    /**
     * 某访客在这一页里赞过哪些帖（V1.1.6 Story 3.1 · AD-7 Rule 2）。
     *
     * <p>🛡 <b>Feed 必须用这个，不要用 {@code existsByPostIdAndUserId}</b> ——
     * 后者是逐条的，搬进循环就是每页 20 次查询（AD-7 明令禁止）。
     *
     * <p>只返回<b>赞过的</b> postId，调用方按「在集合里 = 已赞」判定；
     * 未登录访客根本不该调到这里（应整批短路为 false，见 {@code FeedService}）。
     */
    @Query("SELECT l.postId FROM ContentLike l WHERE l.userId = :userId AND l.postId IN :postIds")
    List<Long> findLikedPostIds(@Param("userId") long userId,
            @Param("postIds") Collection<Long> postIds);

    @Transactional
    void deleteByPostIdAndUserId(long postId, long userId);

    /** 内容删除级联：物理清该帖全部点赞（点赞无保留价值，Story 3.6）。 */
    @Transactional
    void deleteByPostId(long postId);
}
