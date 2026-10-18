package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1：推荐流作者等级加权（V1.4.0 ① Story 3.4 · AC4）—— 需 Docker postgres+redis，<b>云端不跑，待本地验收</b>。
 *
 * <p>⚠️ 不在 ALL Tab 端点上断言「谁排在谁前面」：分数依赖共享测试库那一池内容的赞评分布（P95 现算），会时红时绿
 * （同 {@code FeedRankRoutingIntegrationTest} 的理由）。改为在<b>真实库 + 真实 bean</b> 上断言系数 Map 本身
 * （等级来自真实 {@code getLevels}），排序乘法由 L0 的 {@code FeedRankEngineTest} / {@code FeedRankScorerTest} 钉住；
 * 分类 Tab 是纯时间倒序，可以直接端到端断言。
 *
 * <p>本地跑法：{@code DB_NAME=<新库> ./mvnw -B test -Dtest=FeedAuthorLevelBoostIntegrationTest}（Redis 不 FLUSH）。
 */
class FeedAuthorLevelBoostIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FeedRecommendationService recommendations;

    private void level(long userId, int level, Integer displayLevel) {
        jdbc.update("INSERT INTO growth_accounts (user_id, lifetime_total, level, display_level, version, created_at,"
                + " updated_at) VALUES (?, 0, ?, ?, 0, now(), now())", userId, level, displayLevel);
    }

    private long post(long authorId, Instant at) {
        return jdbc.queryForObject("INSERT INTO content_posts (author_id, type, status, visibility, created_at,"
                + " updated_at, content_version) VALUES (?, 'DAILY', 'PUBLISHED', 'PUBLIC', ?, ?, 1) RETURNING id",
                Long.class, authorId, Timestamp.from(at), Timestamp.from(at));
    }

    @Test
    void onlyParticipatingLv15AuthorsGetTheConfiguredBoost() {
        User lv15 = newUser();
        level(lv15.getId(), 15, null);
        User lv14 = newUser();
        level(lv14.getId(), 14, null);
        User virtual = users.saveAndFlush(User.newVirtual("it-boost-virtual-" + SEQ.incrementAndGet(), "v", null,
                adminActorId()));
        level(virtual.getId(), 1, 15); // 展示等级 15，但不参与成长值

        Instant at = Instant.now();
        List<RankCandidate> candidates = new ArrayList<>();
        long id = 1;
        for (User u : List.of(lv15, lv14, virtual)) {
            candidates.add(new RankCandidate(id++, u.getId(), FeedAttribute.FUN, null, at, 0, 0));
        }

        Map<Long, Double> f = recommendations.authorFactors(candidates, 1.05);
        assertThat(f).isEqualTo(Map.of(lv15.getId(), 1.05));
        assertThat(recommendations.authorFactors(candidates, 1.0)).isEmpty();
    }

    @Test
    void categoryTabStaysPureChronological() throws Exception {
        User lv15 = newUser();
        level(lv15.getId(), 15, null);
        User lv14 = newUser();
        level(lv14.getId(), 14, null);
        Instant base = Instant.now().plusSeconds(1);
        long older = post(lv15.getId(), base);
        long newer = post(lv14.getId(), base.plusMillis(10));

        JsonNode page = json.readTree(mvc.perform(get("/api/v1/content-posts").param("category", "DAILY"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).contains(older, newer);
        assertThat(ids.indexOf(newer)).as("分类 Tab 按时间倒序，不受作者加权影响").isLessThan(ids.indexOf(older));
    }
}
