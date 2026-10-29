package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1：bug 20261012-586 —— 作者刚发的公开帖在推荐序首屏排在自然流最前（顶置位之后），
 * 且翻页不重复。真 PostgreSQL 跑真 JPQL + 真端点。
 *
 * <p>⚠️ 断言只落在「作者本人视角的第 1 条」与「两页之间不重复」这两个<b>确定性</b>事实上，
 * 不断言其余条目的顺序（那取决于共享测试库里的赞评分布）。
 */
class FeedOwnRecentPostIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private JdbcTemplate jdbc;

    private ContentPost publish(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null,
                "own-recent-" + SEQ.incrementAndGet(), List.of()));
    }

    private void backdate(long postId, Duration ago) {
        jdbc.update("UPDATE content_posts SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(ago)), postId);
    }

    private JsonNode feed(long viewerId, String cursor) throws Exception {
        var req = get("/api/v1/content-posts").header("Authorization", userBearer(viewerId));
        if (cursor != null) {
            req = req.param("cursor", cursor);
        }
        String body = mvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            out.add(item.get("id").asLong());
        }
        return out;
    }

    /** 造够两页的他人内容（每条一个作者，避免防扎堆把页填不满）。 */
    private void seedOthers() {
        for (int i = 0; i < 25; i++) {
            publish(newUser().getId());
        }
    }

    // ── 查询层：窗口 / 可见范围 / 软删 / 上限 ─────────────────────────

    @Test
    void recentQueryHonoursWindowVisibilityDeletionAndLimit() {
        User me = newUser();
        ContentPost fresh = publish(me.getId());
        ContentPost old = publish(me.getId());
        backdate(old.getId(), Duration.ofMinutes(11));
        ContentPost privatePost = ContentPost.publish(me.getId(), ContentType.DAILY, null,
                "own-recent-private", List.of());
        privatePost.setVisibility(ContentVisibility.PRIVATE);
        posts.save(privatePost);
        ContentPost deleted = publish(me.getId());
        deleted.softDelete();
        posts.save(deleted);
        ContentPost pending = posts.save(ContentPost.pendingReview(me.getId(), ContentType.DAILY,
                null, "own-recent-pending", List.of(), null));
        ContentPost othersPost = publish(newUser().getId());

        Instant now = Instant.now().plusSeconds(1);
        List<Long> got = posts.findOwnRecentPosts(me.getId(),
                now.minus(FeedRecommendationService.OWN_RECENT_WINDOW), now,
                PageRequest.of(0, 10)).stream().map(ContentPost::getId).toList();

        assertThat(got).contains(fresh.getId(), pending.getId())
                .doesNotContain(old.getId(), privatePost.getId(), deleted.getId(),
                        othersPost.getId());
        // 最新在前
        assertThat(got.indexOf(pending.getId())).isLessThan(got.indexOf(fresh.getId()));
    }

    // ── 端点层：首屏第一条 + 翻页不重复 ─────────────────────────────

    @Test
    void justPublishedPostLeadsTheAuthorsFirstPageAndIsNotRepeated() throws Exception {
        seedOthers();
        User me = newUser();
        ContentPost mine = publish(me.getId());

        JsonNode first = feed(me.getId(), null);
        List<Long> firstIds = ids(first);
        assertThat(firstIds).isNotEmpty();
        assertThat(firstIds.get(0)).as("刚发的帖应排在自然流第一条").isEqualTo(mine.getId());
        assertThat(firstIds).doesNotHaveDuplicates();

        assertThat(first.get("hasMore").asBoolean()).isTrue();
        JsonNode second = feed(me.getId(), first.get("nextCursor").asText());
        assertThat(ids(second)).as("首屏插过的帖不能在第二页再出现")
                .doesNotContain(mine.getId());
    }
}
