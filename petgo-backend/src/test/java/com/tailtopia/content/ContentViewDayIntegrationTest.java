package com.tailtopia.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.content.service.ContentViewDayCleanupJob;
import com.tailtopia.content.web.ContentFeedController;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V1.4.2 social-topic · Story 4.1 · AC8 · L1（需 postgres + redis；云端不跑，待本地验收）。
 *
 * <p>按天浏览：同一用户当天首次 / 再次 / 模拟次日 → 1 / 1 / 2 行；作者本人、{@code countView=false}、无匿名会话头的游客 → 不记；
 * 有会话头的游客 → 记；{@code content_post_views} 次数照旧累加；清理任务删 31 天前、保留 29 天前。
 */
class ContentViewDayIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ContentViewDayCleanupJob cleanupJob;

    private long publish(long userId) throws Exception {
        String resp = mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"DAILY\",\"text\":\"lihat\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    private void open(long postId, Long viewer, String anon, boolean countView) throws Exception {
        var req = get("/api/v1/content-posts/" + postId).param("countView", String.valueOf(countView));
        if (viewer != null) {
            req = req.header(HttpHeaders.AUTHORIZATION, userBearer(viewer));
        }
        if (anon != null) {
            req = req.header(ContentFeedController.ANON_SESSION_HEADER, anon);
        }
        mvc.perform(req).andExpect(status().isOk());
    }

    private int dayRows(long postId) {
        return jdbc.queryForObject("SELECT count(*) FROM content_post_view_days WHERE post_id = ?",
                Integer.class, postId);
    }

    /** 轮询等待异步写入（最多 5 秒）。 */
    private static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(100);
        }
    }

    private void awaitDayRows(long postId, int expected) throws InterruptedException {
        await(() -> dayRows(postId) == expected);
    }

    @Test
    void dailyDedupAndExclusions() throws Exception {
        long author = newUser().getId();
        long viewer = newUser().getId();
        long postId = publish(author);

        open(postId, author, null, true);
        open(postId, viewer, null, false);
        open(postId, null, null, true);
        Thread.sleep(500); // 异步写入：给「不该写」的路径留出落库窗口
        assertThat(dayRows(postId)).isZero();

        open(postId, viewer, null, true);
        awaitDayRows(postId, 1);
        open(postId, viewer, null, true);
        Thread.sleep(500);
        assertThat(dayRows(postId)).isEqualTo(1);

        // 模拟「次日」：把今天那行挪到昨天，再打开一次 → 2 行。
        jdbc.update("UPDATE content_post_view_days SET view_date = view_date - 1 WHERE post_id = ?", postId);
        open(postId, viewer, null, true);
        awaitDayRows(postId, 2);

        open(postId, null, "anonSessionAbc123", true);
        awaitDayRows(postId, 3);

        // AC3 回归：累计表次数照旧（viewer 3 次）。
        await(() -> jdbc.queryForObject(
                "SELECT view_count FROM content_post_views WHERE post_id = ? AND viewer_key = ?",
                Integer.class, postId, "u:" + viewer) == 3);
    }

    @Test
    void cleanupDeletesOlderThan30DaysOnly() throws Exception {
        long postId = publish(newUser().getId());
        LocalDate today = LocalDate.now(ScheduleWindow.WIB);
        jdbc.update("INSERT INTO content_post_view_days (post_id, view_date, viewer_key) VALUES (?, ?, 'u:1')",
                postId, today.minusDays(31));
        jdbc.update("INSERT INTO content_post_view_days (post_id, view_date, viewer_key) VALUES (?, ?, 'u:2')",
                postId, today.minusDays(29));
        cleanupJob.cleanup();
        assertThat(jdbc.queryForList("SELECT viewer_key FROM content_post_view_days WHERE post_id = ?",
                String.class, postId)).containsExactly("u:2");
    }
}
