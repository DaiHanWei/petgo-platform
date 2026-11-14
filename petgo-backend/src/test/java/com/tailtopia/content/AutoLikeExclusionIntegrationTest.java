package com.tailtopia.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.dailyreport.DailyReportQuery;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentLikeRepository.PostLikeCount;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：V1.5.0 likes Story 1.2（PRD 自动点赞 FR-218 · A-AC33 / AC34 / AC39 前半）——
 * 排序 / 统计口径排除自动赞，展示口径包含。需真 PG（云端不跑，本地验收）。
 *
 * <p>为与共享库里其他用例的数据隔离，点赞时间落在一个远古 WIB 日（2001-03-03）。
 */
class AutoLikeExclusionIntegrationTest extends ApiIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2001, 3, 3);
    private static final String TS = "2001-03-03T05:00:00Z"; // WIB 12:00

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentLikeRepository likes;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DailyReportQuery dailyReport;

    private ContentPost savePost(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, "t", List.of(), null));
    }

    private User virtualUser() {
        User u = newUser();
        jdbc.update("UPDATE users SET account_type = 'VIRTUAL', google_sub = 'virtual:' || google_sub WHERE id = ?",
                u.getId());
        return u;
    }

    private void like(long postId, long userId, boolean automated) {
        jdbc.update("INSERT INTO content_likes (post_id, user_id, created_at, is_automated) "
                + "VALUES (?, ?, ?::timestamptz, ?)", postId, userId, TS, automated);
    }

    private static Map<Long, Long> asMap(List<PostLikeCount> rows) {
        return rows.stream().collect(Collectors.toMap(PostLikeCount::getPostId, PostLikeCount::getLikeCount));
    }

    /** AC7：两条帖除「B 多 3 条自动赞」外相同 → 排序 / 后台窗口口径相同，展示口径 B 多 3。 */
    @Test
    void automatedLikesAreInvisibleToRankingButVisibleToDisplay() {
        User author = newUser();
        ContentPost a = savePost(author.getId());
        ContentPost b = savePost(author.getId());
        for (int i = 0; i < 2; i++) {
            User real = newUser();
            like(a.getId(), real.getId(), false);
            like(b.getId(), real.getId(), false);
        }
        for (int i = 0; i < 3; i++) {
            like(b.getId(), virtualUser().getId(), true);
        }
        List<Long> ids = List.of(a.getId(), b.getId());

        Map<Long, Long> organic = asMap(likes.countOrganicByPostIdIn(ids));
        assertThat(organic.get(a.getId())).isEqualTo(2L);
        assertThat(organic.get(b.getId())).isEqualTo(2L);

        Map<Long, Long> display = asMap(likes.countByPostIdIn(ids));
        assertThat(display.get(a.getId())).isEqualTo(2L);
        assertThat(display.get(b.getId())).isEqualTo(5L);

        Map<Long, Long> window = asMap(likes.countInWindow(Instant.parse("2001-03-03T00:00:00Z"),
                Instant.parse("2001-03-04T00:00:00Z"), PageRequest.of(0, 1000)));
        assertThat(window.get(a.getId())).isEqualTo(window.get(b.getId())).isEqualTo(2L);

        // 主页 Suka（展示口径）包含自动赞
        assertThat(likes.sumLikesOnPublicPostsByAuthor(author.getId())).isEqualTo(7L);
    }

    /** AC4：当天 3 条真人赞 + 2 条虚拟号的自动赞 → 日报「点赞数」= 3。 */
    @Test
    void dailyReportLikesCountOnlyRealLikers() {
        long before = dailyReport.metricsOf(DAY).likes();
        User author = newUser();
        ContentPost p = savePost(author.getId());
        for (int i = 0; i < 3; i++) {
            like(p.getId(), newUser().getId(), false);
        }
        for (int i = 0; i < 2; i++) {
            like(p.getId(), virtualUser().getId(), true);
        }

        assertThat(dailyReport.metricsOf(DAY).likes() - before).isEqualTo(3L);
    }
}
