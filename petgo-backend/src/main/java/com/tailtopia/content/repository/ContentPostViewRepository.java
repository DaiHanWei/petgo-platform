package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentPostView;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 内容浏览记录读写（2026-08-31）。
 */
public interface ContentPostViewRepository extends JpaRepository<ContentPostView, Long> {

    /**
     * 记一次浏览：无行则插入（次数 1），有行则次数 +1。
     *
     * <p>🔴 必须是数据库层的 UPSERT，不能读回实体改了再 save ——
     * 同一人两个请求并发打开时读改写会丢一次计数，且唯一约束会让后到的 insert 直接抛错。
     */
    @Modifying
    @Query(value = "INSERT INTO content_post_views "
            + "(post_id, viewer_key, view_count, first_viewed_at, last_viewed_at) "
            + "VALUES (:postId, :viewerKey, 1, :now, :now) "
            + "ON CONFLICT (post_id, viewer_key) DO UPDATE SET "
            + "view_count = content_post_views.view_count + 1, last_viewed_at = :now",
            nativeQuery = true)
    void upsertView(@Param("postId") long postId, @Param("viewerKey") String viewerKey,
            @Param("now") Instant now);

    /** 批量浏览统计（后台列表整页一次取，禁逐行）。没被看过的帖不在结果里，调用方默认 0。 */
    @Query("SELECT v.postId AS postId, SUM(v.viewCount) AS viewTotal, COUNT(v) AS viewerTotal "
            + "FROM ContentPostView v WHERE v.postId IN :postIds GROUP BY v.postId")
    List<PostViewStat> statsByPostIdIn(@Param("postIds") Collection<Long> postIds);

    /** 批量浏览统计投影（postId → 次数 / 人数）。 */
    interface PostViewStat {
        Long getPostId();

        long getViewTotal();

        long getViewerTotal();
    }
}
