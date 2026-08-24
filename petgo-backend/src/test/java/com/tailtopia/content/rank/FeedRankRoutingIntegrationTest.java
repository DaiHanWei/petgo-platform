package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.FeedCursor;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * L1：ALL Tab 与非 ALL Tab 在<b>端点层</b>确实是两条独立路径（Story 16.3 · AC1）。
 *
 * <h2>🔴 为什么不用「顺序不一样」来断言</h2>
 * 那个断言依赖打分结果，而分数取决于共享测试库里那一池内容的赞评分布（P95 现算）——
 * 会得到一条<b>时不时红、且红的时候跟路由毫无关系</b>的测试。
 *
 * <p>改用一个<b>结构性</b>事实：两条路径的游标<b>本来就是两种不同的东西</b> ——
 * 时间倒序发的是 {@code (createdAt, id)}，推荐序发的是 {@code (seed, consumed)}。
 * 这跟内容多少、赞评多少都无关，是确定的。
 */
class FeedRankRoutingIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;

    private JsonNode feed(String query) throws Exception {
        String body = mvc.perform(get("/api/v1/content-posts" + query))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** 造够两页的内容（每条一个作者，避免防扎堆把页填不满）。 */
    private void seedTwoPages() {
        for (int i = 0; i < 25; i++) {
            posts.save(ContentPost.publish(newUser().getId(), ContentType.DAILY, null,
                    "routing-" + SEQ.incrementAndGet(), List.of()));
        }
    }

    /**
     * 🔴 ALL Tab 发的是<b>推荐序游标</b>：能按 {@code (seed, consumed)} 解开，
     * 且<b>解不成</b>时间倒序那种游标。
     */
    @Test
    void allTabIssuesARankCursor() throws Exception {
        seedTwoPages();

        JsonNode page = feed("");
        String cursor = page.get("nextCursor").asText();

        assertThat(page.get("hasMore").asBoolean()).isTrue();
        FeedRankCursor rank = FeedRankCursor.decode(cursor);
        assertThat(rank.seed()).isNotBlank();
        assertThat(rank.consumed()).isPositive();
        // 🛡 两种游标不是一回事：推荐序游标不该能被当成时间倒序游标解开
        assertThat(catching(() -> FeedCursor.decode(cursor)))
                .as("推荐序游标被时间倒序解码器接受了 —— 说明两条路径的游标撞在一起了")
                .isTrue();
    }

    /** 🛡 非 ALL Tab 发的仍是<b>时间倒序游标</b> —— 那条路径一行没动。 */
    @Test
    void categoryTabStillIssuesAChronoCursor() throws Exception {
        seedTwoPages();

        JsonNode page = feed("?category=DAILY");
        String cursor = page.get("nextCursor").asText();

        assertThat(page.get("hasMore").asBoolean()).isTrue();
        FeedCursor chrono = FeedCursor.decode(cursor);
        assertThat(chrono.id()).isPositive();
        assertThat(chrono.createdAt()).isBefore(Instant.now().plusSeconds(60));
    }

    /** 🛡 非 ALL Tab 的返回顺序仍是<b>严格</b> {@code created_at DESC}（推荐序不保证这一点）。 */
    @Test
    void categoryTabIsStrictlyReverseChronological() throws Exception {
        seedTwoPages();

        JsonNode page = feed("?category=DAILY");
        List<Instant> times = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            times.add(Instant.parse(item.get("createdAt").asText()));
        }

        assertThat(times).hasSizeGreaterThan(1);
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).as("第 %d 条比第 %d 条新 —— 时间倒序被破坏了", i, i - 1)
                    .isBeforeOrEqualTo(times.get(i - 1));
        }
    }

    /** 用推荐序游标去请求分类 Tab（客户端切 Tab 时若忘了清游标）不得 500。 */
    @Test
    void crossPathCursorIsRejectedNotCrashing() throws Exception {
        seedTwoPages();
        String rankCursor = feed("").get("nextCursor").asText();

        mvc.perform(get("/api/v1/content-posts")
                        .param("category", "DAILY")
                        .param("cursor", rankCursor))
                .andExpect(status().isUnprocessableEntity());
    }

    /**
     * 🔴 <b>反方向也必须被拒</b>：时间倒序游标喂给 ALL Tab。
     *
     * <p>这条比上一条更要紧：没有种子前缀时它<b>不会报错</b> —— 时间倒序游标
     * {@code "<micros>:<id>"} 会被解成 seed=那串毫秒数、consumed=id，
     * 用户拿到一个不存在种子的任意偏移页。不崩、不记错、没人查得出来。
     */
    @Test
    void chronoCursorIsRejectedByTheAllTab() throws Exception {
        seedTwoPages();
        String chronoCursor = feed("?category=DAILY").get("nextCursor").asText();

        mvc.perform(get("/api/v1/content-posts").param("cursor", chronoCursor))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── V1.1.6 Story 16.5：rankMode 由服务端下发 ─────────────────────

    /**
     * 🔴 两条路径下发的 {@code rankMode} 必须不同。
     *
     * <p>这是客户端唯一能分辨排序路径的依据 —— 降级链级别 4 会让 ALL Tab 也走时间倒序，
     * 那对客户端完全无感。客户端按「是不是 ALL Tab」自己判断的话，
     * 降级期间的数据会被算进推荐序的效果里，而那正是 FR-95 参数校准要看的数。
     */
    @Test
    void rankModeTellsTheTwoPathsApart() throws Exception {
        seedTwoPages();

        assertThat(feed("").get("rankMode").asText()).isEqualTo("recommend");
        assertThat(feed("?category=DAILY").get("rankMode").asText()).isEqualTo("chrono");
    }

    /** 🛡 每一页都带（翻页时客户端要按页合并，某页缺了会让整段变成 unknown）。 */
    @Test
    void everyPageCarriesRankMode() throws Exception {
        seedTwoPages();

        JsonNode first = feed("");
        assertThat(first.get("rankMode").asText()).isEqualTo("recommend");
        JsonNode second = feed("?cursor=" + first.get("nextCursor").asText());
        assertThat(second.get("rankMode").asText()).isEqualTo("recommend");
    }

    private static boolean catching(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
