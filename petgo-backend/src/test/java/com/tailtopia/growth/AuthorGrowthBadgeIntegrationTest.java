package com.tailtopia.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthBadge;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1：作者等级批量下发（V1.4.0 ① Story 3.1 · AC4）—— 需 Docker postgres+redis，<b>云端不跑，待本地验收</b>。
 *
 * <p>本地跑法：{@code DB_NAME=<新库> ./mvnw -B test -Dtest=AuthorGrowthBadgeIntegrationTest}（Redis 不 FLUSH）。
 */
class AuthorGrowthBadgeIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GrowthQueryService growth;

    @Autowired
    private AccountQueryService accounts;

    private void account(long userId, Integer level, Integer displayLevel, String frameChoice) {
        jdbc.update("INSERT INTO growth_accounts (user_id, lifetime_total, level, display_level, frame_choice,"
                + " version, created_at, updated_at) VALUES (?, 0, ?, ?, ?, 0, now(), now())"
                + " ON CONFLICT (user_id) DO UPDATE SET level = EXCLUDED.level,"
                + " display_level = EXCLUDED.display_level, frame_choice = EXCLUDED.frame_choice",
                userId, level == null ? 1 : level, displayLevel, frameChoice);
    }

    private long publicPost(long authorId) {
        Instant at = Instant.now();
        return jdbc.queryForObject("INSERT INTO content_posts (author_id, type, status, visibility, created_at,"
                + " updated_at, content_version) VALUES (?, 'DAILY', 'PUBLISHED', 'PUBLIC', ?, ?, 1) RETURNING id",
                Long.class, authorId, Timestamp.from(at), Timestamp.from(at));
    }

    private User virtual(int displayLevel) {
        User v = users.saveAndFlush(User.newVirtual("it-badge-virtual-" + SEQ.incrementAndGet(), "v", null,
                adminActorId()));
        account(v.getId(), 1, displayLevel, "NONE");
        return v;
    }

    @Test
    void badgeRulesAgainstRealSchema() {
        User fresh = newUser(); // 无账户行
        User guardian = newUser();
        account(guardian.getId(), 12, null, "UPGRADED");
        User none = newUser();
        account(none.getId(), 12, null, "NONE");
        User virtual = virtual(15);
        User deleted = newUser();
        deleted.anonymizeForDeletion(Instant.now());
        users.saveAndFlush(deleted);
        User deactivated = newUser();
        deactivated.deactivate();
        users.saveAndFlush(deactivated);
        // P-7：官方号与虚拟号同处理（展示等级 + 自动最高档，不看 frame_choice）。
        User official = users.saveAndFlush(User.newAdmin("it-badge-admin-" + SEQ.incrementAndGet() + "@x.io",
                "TailUs", "x"));
        account(official.getId(), 1, 12, "NONE");

        Map<Long, GrowthBadge> b = growth.getBadges(List.of(fresh.getId(), guardian.getId(), none.getId(),
                virtual.getId(), deleted.getId(), deactivated.getId(), official.getId(), -1L));

        assertThat(b.get(official.getId())).isEqualTo(new GrowthBadge(12, GrowthTier.GUARDIAN, FrameChoice.UPGRADED));
        assertThat(growth.getLevel(official.getId())).isEqualTo(GrowthLevel.NOT_PARTICIPATING);

        assertThat(b.get(fresh.getId())).isEqualTo(new GrowthBadge(1, GrowthTier.NEWBIE, null));
        assertThat(b.get(guardian.getId())).isEqualTo(new GrowthBadge(12, GrowthTier.GUARDIAN, FrameChoice.UPGRADED));
        assertThat(b.get(none.getId())).isEqualTo(new GrowthBadge(12, GrowthTier.GUARDIAN, null));
        assertThat(b.get(virtual.getId())).isEqualTo(new GrowthBadge(15, GrowthTier.STAR, FrameChoice.ANIMATED));
        assertThat(b).doesNotContainKeys(deleted.getId(), deactivated.getId(), -1L);
        // 展示与权益不混用：虚拟号权益仍不参与。
        assertThat(growth.getLevel(virtual.getId())).isEqualTo(GrowthLevel.NOT_PARTICIPATING);

        Map<Long, AuthorView> views = accounts.findAuthorViews(List.of(guardian.getId(), deactivated.getId()));
        assertThat(views.get(guardian.getId()).growth()).isNotNull();
        assertThat(views.get(deactivated.getId()).growth()).isNull();
    }

    @Test
    void detailAndFeedCarryAuthorFields() throws Exception {
        User viewer = newUser();
        User author = newUser();
        account(author.getId(), 12, null, null);
        long postId = publicPost(author.getId());
        User virtual = virtual(15);
        publicPost(virtual.getId());

        JsonNode detail = json.readTree(mvc.perform(get("/api/v1/content-posts/" + postId)
                        .header("Authorization", userBearer(viewer.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("authorLevel").asInt()).isEqualTo(12);
        assertThat(detail.get("authorTier").asText()).isEqualTo("GUARDIAN");
        assertThat(detail.get("authorFrame").asText()).isEqualTo("UPGRADED");

        JsonNode feed = json.readTree(mvc.perform(get("/api/v1/content-posts")
                        .header("Authorization", userBearer(viewer.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        boolean sawVirtual = false;
        for (JsonNode item : feed.get("items")) {
            if (item.get("authorId").asLong() == virtual.getId()) {
                sawVirtual = true;
                assertThat(item.get("authorLevel").asInt()).isEqualTo(15);
                assertThat(item.get("authorTier").asText()).isEqualTo("STAR");
                assertThat(item.get("authorFrame").asText()).isEqualTo("ANIMATED");
            }
            if (item.path("authorDeleted").asBoolean()) {
                assertThat(item.has("authorLevel")).isFalse();
            }
        }
        // 刚发的帖在 ALL 推荐流首页（共享库下若被别的测试顶出首页，此断言会提示改造数据）。
        assertThat(sawVirtual).as("virtual author post on first feed page").isTrue();
    }
}
