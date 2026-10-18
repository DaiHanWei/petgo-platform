package com.tailtopia.collect.repository;

import com.tailtopia.collect.domain.ContentCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 帖子收藏读写（V1.4.0 ④ Story 2.1 · AD-7 / AD-8）。
 *
 * <p>🔴 写入只用 {@link #insertIfAbsent}：返回影响行数，1 = 真正新增（决定发不发 {@code ContentCollectedEvent}）。
 * 不照抄点赞的「exists → save → 吞唯一键异常」——Hibernate 抛约束异常会把事务标成 rollback-only。
 */
public interface ContentCollectionRepository extends JpaRepository<ContentCollection, Long> {

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO content_collections (user_id, post_id) VALUES (:userId, :postId)
            ON CONFLICT (user_id, post_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("userId") long userId, @Param("postId") long postId);

    @Modifying
    @Query("delete from ContentCollection c where c.userId = :userId and c.postId = :postId")
    int deleteByUserIdAndPostId(@Param("userId") long userId, @Param("postId") long postId);

    boolean existsByUserIdAndPostId(long userId, long postId);

    /** 作者视角收藏数：不计作者自己（PRD AC10）。 */
    @Query("select count(c) from ContentCollection c where c.postId = :postId and c.userId <> :authorId")
    long countByPostIdExcludingUser(@Param("postId") long postId, @Param("authorId") long authorId);

    /**
     * 我的帖子收藏分页（V1.4.0 ④ Story 2.2）：按<b>收藏行</b> {@code created_at DESC, id DESC} keyset，
     * 走 {@code idx_content_collections_user_created}。不在 SQL 里按可见性过滤（不可见行照样占位）。
     * 调用方传 {@code PageRequest.of(0, 21)}。
     */
    @Query("""
            SELECT c FROM ContentCollection c
            WHERE c.userId = :me
              AND (c.createdAt < :ts OR (c.createdAt = :ts AND c.id < :lastId))
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    java.util.List<ContentCollection> findPage(@Param("me") long me, @Param("ts") java.time.Instant ts,
            @Param("lastId") long lastId, org.springframework.data.domain.Pageable pageable);

    /** 注销：删本人全部帖子收藏。 */
    @Modifying
    @Query("delete from ContentCollection c where c.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}
