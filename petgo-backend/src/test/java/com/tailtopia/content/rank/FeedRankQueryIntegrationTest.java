package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

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
}
