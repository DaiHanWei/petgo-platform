package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.domain.ContentReport;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.repository.ContentReportRepository;
import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.domain.UserHideRelation;
import com.tailtopia.social.repository.UserHideRelationRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：推荐序候选池的过滤口径（Story 16.3 · AC2 / AC4）—— 真 PostgreSQL 跑真 JPQL。
 *
 * <h2>🔴 为什么断言落在<b>查询层</b>而不是首页接口</h2>
 * 首页接口返回的是「排序后的第一页 20 条」。在共享测试库里池子有几千条，
 * 一条刚造的 0 赞内容<b>落到第 20 名之后完全正常</b> —— 拿它断言「在席」会得到一条
 * 时不时红、且红的时候与过滤毫无关系的测试。
 * 而过滤本身是查询的属性，在这一层断言既确定又直指要害。
 */
class FeedRankQueryIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private ContentReportRepository reports;

    @Autowired
    private UserHideRelationRepository hides;

    @Autowired
    private JdbcTemplate jdbc;

    private ContentPost publish(long authorId, ContentType type) {
        return posts.save(ContentPost.publish(authorId, type, null, "rank-pool-" + SEQ.incrementAndGet(),
                List.of()));
    }

    /** 候选池里有没有这一条（用 findRankableByIds 精确问，不受池子上界与排序影响）。 */
    private boolean poolContains(Long viewerId, long postId) {
        return !posts.findRankableByIds(List.of(postId), viewerId != null, viewerId).isEmpty();
    }

    // ── AC2：六条过滤 ───────────────────────────────────────────────

    @Test
    void publishedPublicPostIsInThePool() {
        User author = newUser();
        User viewer = newUser();
        ContentPost p = publish(author.getId(), ContentType.DAILY);

        assertThat(poolContains(viewer.getId(), p.getId())).isTrue();
        assertThat(poolContains(null, p.getId())).isTrue(); // 游客同样能看到
    }

    @Test
    void softDeletedPostIsNotInThePool() {
        ContentPost p = publish(newUser().getId(), ContentType.DAILY);
        p.softDelete();
        posts.save(p);

        assertThat(poolContains(newUser().getId(), p.getId())).isFalse();
    }

    @Test
    void privatePostIsNotInThePool() {
        ContentPost p = publish(newUser().getId(), ContentType.GROWTH_MOMENT);
        p.setVisibility(ContentVisibility.PRIVATE);
        posts.save(p);

        assertThat(poolContains(newUser().getId(), p.getId())).isFalse();
    }

    /** 挂起帖：🛡 作者本人看得到（不感知），他人零泄漏。 */
    @Test
    void underReviewPostIsVisibleOnlyToItsAuthor() {
        User author = newUser();
        ContentPost p = publish(author.getId(), ContentType.DAILY);
        p.applyReportHold();
        posts.save(p);

        assertThat(poolContains(author.getId(), p.getId())).isTrue();
        assertThat(poolContains(newUser().getId(), p.getId())).isFalse();
        assertThat(poolContains(null, p.getId())).isFalse(); // 游客
    }

    /**
     * 🔴 <b>挂起帖不进候选池 —— 连作者自己的也不进</b>（AC2）。
     *
     * <p>它<b>不占算法槽位、不参与配比与打分</b>：进池就要和全平台内容抢分数，抢不到
     * 就等于「刚发的帖从首页消失」，而作者只会以为发布失败了。
     * 它走 {@link ContentPostRepository#findOwnPendingPosts} 单独取、首屏置顶插回。
     */
    @Test
    void pendingPostsStayOutOfTheCandidatePoolEvenForTheirAuthor() {
        User author = newUser();
        ContentPost p = publish(author.getId(), ContentType.DAILY);
        p.applyReportHold();
        posts.save(p);

        List<Long> pool = posts.findRankCandidatePool(true, author.getId(),
                PageRequest.of(0, 200)).stream().map(ContentPost::getId).toList();
        assertThat(pool).doesNotContain(p.getId());

        // 但单独那条路取得到，且只取到本人的
        assertThat(posts.findOwnPendingPosts(author.getId(), PageRequest.of(0, 10)))
                .extracting(ContentPost::getId).contains(p.getId());
        assertThat(posts.findOwnPendingPosts(newUser().getId(), PageRequest.of(0, 10)))
                .extracting(ContentPost::getId).doesNotContain(p.getId());
    }

    /** 🛡 软删的挂起帖对所有人隐藏 —— 含作者（拒绝即软删）。 */
    @Test
    void softDeletedPendingPostIsHiddenFromItsAuthorToo() {
        User author = newUser();
        ContentPost p = publish(author.getId(), ContentType.DAILY);
        p.applyReportHold();
        p.softDelete();
        posts.save(p);

        assertThat(posts.findOwnPendingPosts(author.getId(), PageRequest.of(0, 10)))
                .extracting(ContentPost::getId).doesNotContain(p.getId());
    }

    /**
     * 🛡 举报者隐藏与账号级隐藏是<b>两条并列的独立条件，不合并</b>（AD-9）。
     *
     * <p>🔴 补充 PRD 写它们「可合并为一次过滤」——<b>那与代码不符</b>：
     * 一条藏<b>一条帖</b>、一条藏<b>一个人</b>。这条测试就是钉住这个区分：
     * 举报了 A 的某一条，A 的<b>另一条</b>仍然可见；而拉黑 A 之后两条都不可见。
     */
    @Test
    void reportHidesOnePostWhileBlockHidesTheWholeAuthor() {
        User author = newUser();
        User viewer = newUser();
        ContentPost one = publish(author.getId(), ContentType.DAILY);
        ContentPost two = publish(author.getId(), ContentType.DAILY);

        // 举报其中一条 → 只藏那一条
        reports.save(ContentReport.create(one.getId(), viewer.getId(), ReportReason.INAPPROPRIATE));
        assertThat(poolContains(viewer.getId(), one.getId())).isFalse();
        assertThat(poolContains(viewer.getId(), two.getId())).as("举报只藏一条帖").isTrue();

        // 拉黑作者 → 两条都藏
        hides.save(UserHideRelation.create(viewer.getId(), author.getId(), HideSource.BLOCK));
        assertThat(poolContains(viewer.getId(), two.getId())).as("拉黑藏整个人").isFalse();

        // 🛡 只对这个查看者生效
        assertThat(poolContains(newUser().getId(), two.getId())).isTrue();
    }

    // ── AC2：候选池有上界且不带分类过滤 ─────────────────────────────

    /** 候选池<b>不按分类过滤</b>（三类内容都在池子里，属性穿插靠引擎而不是靠 SQL）。 */
    @Test
    void poolCarriesAllThreeContentTypes() {
        User author = newUser();
        publish(author.getId(), ContentType.DAILY);
        publish(author.getId(), ContentType.KNOWLEDGE);

        List<ContentPost> pool = posts.findRankCandidatePool(false, null, PageRequest.of(0, 200));

        assertThat(pool.stream().map(ContentPost::getType).distinct())
                .contains(ContentType.DAILY, ContentType.KNOWLEDGE);
    }

    /** 🔴 候选池必须有上界，且按 created_at DESC 取最近的那一批。 */
    @Test
    void poolIsBoundedAndTakesTheMostRecent() {
        publish(newUser().getId(), ContentType.DAILY);

        List<ContentPost> pool = posts.findRankCandidatePool(false, null, PageRequest.of(0, 5));

        assertThat(pool).hasSizeLessThanOrEqualTo(5);
        for (int i = 1; i < pool.size(); i++) {
            assertThat(pool.get(i).getCreatedAt())
                    .isBeforeOrEqualTo(pool.get(i - 1).getCreatedAt());
        }
        assertThat(pool).allSatisfy(p -> {
            assertThat(p.getDeletedAt()).isNull();
            assertThat(p.getVisibility()).isEqualTo(ContentVisibility.PUBLIC);
            assertThat(p.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        });
    }

    // ── V1.3.4 Story 1.1：教育类独立候选池（FR-122）─────────────────

    /** {@code created_at} 实体 updatable=false，回拨时间走 jdbc。 */
    private void backdate(long postId, Duration ago) {
        jdbc.update("UPDATE content_posts SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(ago)), postId);
    }

    private List<Long> eduPool(Long viewerId) {
        return posts.findEduCandidatePool(viewerId != null, viewerId,
                Instant.now().minus(Duration.ofDays(90)), PageRequest.of(0, 100_000)).stream()
                .map(ContentPost::getId).toList();
    }

    /** AC1.4：教育池覆盖近 90 天、只收 KNOWLEDGE；主池（被新帖占满）覆盖不到 60 天前的。 */
    @Test
    void eduPoolCoversNinetyDaysAndOnlyKnowledge() {
        long author = newUser().getId();
        ContentPost k91 = publish(author, ContentType.KNOWLEDGE);
        ContentPost k60 = publish(author, ContentType.KNOWLEDGE);
        ContentPost k5 = publish(author, ContentType.KNOWLEDGE);
        ContentPost d5 = publish(author, ContentType.DAILY);
        backdate(k91.getId(), Duration.ofDays(91));
        backdate(k60.getId(), Duration.ofDays(60));
        backdate(k5.getId(), Duration.ofDays(5));
        backdate(d5.getId(), Duration.ofDays(5));
        // 主池上界 60：造 60 条新 DAILY 把它占满（模拟生产「主池只覆盖近 10 天」）
        for (int i = 0; i < 60; i++) {
            publish(author, ContentType.DAILY);
        }

        List<Long> edu = eduPool(null);
        assertThat(edu).contains(k60.getId(), k5.getId()).doesNotContain(k91.getId(), d5.getId());
        List<Long> main = posts.findRankCandidatePool(false, null, PageRequest.of(0, 60)).stream()
                .map(ContentPost::getId).toList();
        assertThat(main).doesNotContain(k60.getId());
    }

    /** AC2.2 / AC6：教育池套的是主池同一份过滤；游客跳过举报 / 屏蔽两项。 */
    @Test
    void eduPoolAppliesEveryFilter() {
        User viewer = newUser();
        long author = newUser().getId();
        long blockedAuthor = newUser().getId();
        ContentPost control = publish(author, ContentType.KNOWLEDGE);
        ContentPost blocked = publish(blockedAuthor, ContentType.KNOWLEDGE);
        ContentPost reported = publish(author, ContentType.KNOWLEDGE);
        ContentPost deleted = publish(author, ContentType.KNOWLEDGE);
        ContentPost privatePost = publish(author, ContentType.KNOWLEDGE);
        ContentPost held = publish(author, ContentType.KNOWLEDGE);
        hides.save(UserHideRelation.create(viewer.getId(), blockedAuthor, HideSource.BLOCK));
        reports.save(ContentReport.create(reported.getId(), viewer.getId(), ReportReason.INAPPROPRIATE));
        deleted.softDelete();
        posts.save(deleted);
        privatePost.setVisibility(ContentVisibility.PRIVATE);
        posts.save(privatePost);
        held.applyReportHold();
        posts.save(held);
        for (ContentPost p : List.of(control, blocked, reported, deleted, privatePost, held)) {
            backdate(p.getId(), Duration.ofDays(60));
        }

        List<Long> forViewer = eduPool(viewer.getId());
        assertThat(forViewer).contains(control.getId()).doesNotContain(blocked.getId(),
                reported.getId(), deleted.getId(), privatePost.getId(), held.getId());
        List<Long> forGuest = eduPool(null);
        assertThat(forGuest).contains(control.getId(), blocked.getId(), reported.getId())
                .doesNotContain(deleted.getId(), privatePost.getId(), held.getId());
    }

    /** AC2.2：作者被屏蔽的 60 天前科普不出现在该用户 ALL Tab 任何一页。 */
    @Test
    void blockedAuthorsKnowledgeNeverReachesAnyPage() throws Exception {
        User viewer = newUser();
        long blockedAuthor = newUser().getId();
        ContentPost k = publish(blockedAuthor, ContentType.KNOWLEDGE);
        backdate(k.getId(), Duration.ofDays(60));
        hides.save(UserHideRelation.create(viewer.getId(), blockedAuthor, HideSource.BLOCK));

        String cursor = null;
        for (int page = 0; page < 50; page++) {
            var req = get("/api/v1/content-posts").header("Authorization", userBearer(viewer.getId()));
            if (cursor != null) {
                req = req.param("cursor", cursor);
            }
            String body = mvc.perform(req).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("\"id\":" + k.getId() + ",");
            cursor = extractCursor(body);
            if (cursor == null) {
                break;
            }
        }
    }

    /** AC4 L1：部分索引已建且谓词正确。 */
    @Test
    void knowledgePartialIndexExists() {
        List<String> defs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_content_posts_knowledge_feed'",
                String.class);
        assertThat(defs).hasSize(1);
        assertThat(defs.get(0)).contains("'KNOWLEDGE'::text").contains("deleted_at IS NULL");
    }

    private static String extractCursor(String body) {
        int i = body.indexOf("\"nextCursor\":\"");
        if (i < 0) {
            return null;
        }
        int start = i + "\"nextCursor\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
