package com.tailtopia.content.repository;

import com.tailtopia.content.domain.ContentPostView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    @Query(value = "INSERT INTO content_post_views "
            + "(post_id, viewer_key, view_count, first_viewed_at, last_viewed_at) "
            + "VALUES (:postId, :viewerKey, 1, :now, :now) "
            + "ON CONFLICT (post_id, viewer_key) DO UPDATE SET "
            + "view_count = content_post_views.view_count + 1, last_viewed_at = :now",
            nativeQuery = true)
    void upsertView(@Param("postId") long postId, @Param("viewerKey") String viewerKey,
            @Param("now") Instant now);

    /**
     * 按天浏览去重（V1.4.2 social-topic Story 4.1 · D-3 · FR-155）：同一观看者同一帖同一 WIB 日只记一行。
     * 只供话题热度（4.2），后台两列不读它。{@code viewDate} 一律 {@code LocalDate.now(ScheduleWindow.WIB)}（P-6）。
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO content_post_view_days (post_id, view_date, viewer_key) "
            + "VALUES (:postId, :viewDate, :viewerKey) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    void insertViewDay(@Param("postId") long postId, @Param("viewDate") LocalDate viewDate,
            @Param("viewerKey") String viewerKey);

    /** 清理按天浏览：删 {@code view_date < cutoff}（保留 30 天，{@code ContentViewDayCleanupJob}）。返回删除行数。 */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM content_post_view_days WHERE view_date < :cutoff", nativeQuery = true)
    int deleteViewDaysBefore(@Param("cutoff") LocalDate cutoff);

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
