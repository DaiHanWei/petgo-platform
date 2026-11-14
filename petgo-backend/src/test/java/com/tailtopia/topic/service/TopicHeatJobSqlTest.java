package com.tailtopia.topic.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * L0 · V1.4.2 social-topic Story 4.2 · AC7：热度 SQL 由 {@code TopicHeat} 常量拼出、窗口口径、锁语句。
 * 真跑 SQL 的断言在 L1 {@code TopicApiIntegrationTest}（待本地验收）。
 */
class TopicHeatJobSqlTest {

    @Test
    void sqlIsBuiltFromWeightConstants() {
        String sql = TopicHeatJob.RECOMPUTE_SQL;
        assertThat(sql).doesNotContain("%s");
        assertThat(sql).contains("COALESCE(ap.new_posts, 0) * 1")
                .contains("COALESCE(av.viewer_days, 0) * 0.1")
                .contains("COALESCE(al.likes, 0) * 1")
                .contains("COALESCE(ac.comments, 0) * 1");
    }

    @Test
    void sqlUpdatesAllTopicsWithoutTouchingUpdatedAt() {
        String sql = TopicHeatJob.RECOMPUTE_SQL;
        // 以 topics 为驱动表 LEFT JOIN 聚合：无数据的话题算出 0，不残留旧值
        assertThat(sql).contains("FROM topics t2").contains("LEFT JOIN agg_posts");
        assertThat(sql).doesNotContain("updated_at");
    }

    @Test
    void commentCountExcludesShadowComments() {
        String sql = TopicHeatJob.RECOMPUTE_SQL;
        assertThat(sql).contains("c.moderation_status = 'VISIBLE'").contains("c.deleted_at IS NULL");
        // R2：帖子作者隐藏了评论者 / 父评论是影子评论
        assertThat(sql).contains("h.holder_id = v.author_id AND h.target_id = c.author_id");
        assertThat(sql).contains("WHERE pc.id = c.parent_id");
    }

    /** 复审采纳：可计入帖子与话题页 AC2 同口径——作者未注销且 ACTIVE。 */
    @Test
    void countablePostsRequireActiveAuthor() {
        assertThat(TopicHeatJob.RECOMPUTE_SQL)
                .contains("JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL AND u.status = 'ACTIVE'")
                .contains("p.deleted_at IS NULL AND p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC'");
    }

    /** V1.5.0 自动点赞 FR-218 第 3 项：话题热度的点赞聚合排除自动赞。 */
    @Test
    void likeAggregationExcludesAutomatedLikes() {
        assertThat(TopicHeatJob.RECOMPUTE_SQL)
                .containsPattern("JOIN content_likes l ON l\\.post_id = v\\.post_id AND l\\.created_at >= :since\\s+"
                        + "AND NOT l\\.is_automated");
    }

    @Test
    void usesTryLockToSkipConcurrentRuns() {
        assertThat(TopicHeatJob.TRY_LOCK_SQL).isEqualTo("SELECT pg_try_advisory_xact_lock(hashtext('topic_heat'))");
    }

    @Test
    void windows() {
        Instant now = Instant.parse("2026-11-04T18:30:00Z"); // WIB = 2026-11-05 01:30
        assertThat(TopicHeatJob.since(now)).isEqualTo(Instant.parse("2026-10-28T18:30:00Z"));
        // 含今天（WIB 11-05）共 7 个 WIB 日 → 10-30 起
        assertThat(TopicHeatJob.fromDate(now)).isEqualTo(LocalDate.parse("2026-10-30"));
    }
}
