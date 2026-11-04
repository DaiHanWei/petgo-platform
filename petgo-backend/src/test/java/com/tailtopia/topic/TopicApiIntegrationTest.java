package com.tailtopia.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentPinService;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.topic.service.TopicHeatJob;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * V1.4.2 social-topic · Story 4.2 · L1（需 postgres + redis；<b>云端不跑，待本地验收</b>）。
 *
 * <p>覆盖 AC1 详情、AC2 latest / popular（可见性四件套逐条、翻页、comment_weight、R2 影子评论、422）、
 * AC3 联想（401 / 403 / 前缀 / {@code _} 不通配）、AC4 首页话题行、AC5 推荐、AC6 话题坑位与让位、
 * AC7 热度任务（窗口 / R2 / advisory lock 跳过）、AC8 禁用 / 隐藏对 {@code topicNames} 的影响。
 * 数据：话题与关联用 {@link JdbcTemplate} 直插，帖子用 {@code ContentPost.publish} + {@code posts.save}
 * （写法照 {@code FollowingFeedIntegrationTest}）。
 */
class TopicApiIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private ContentPinService pinService;

    @Autowired
    private TopicHeatJob heatJob;

    @Autowired
    private DataSource dataSource;

    // ---------------------------------------------------------------- fixtures

    private static String uniq(String base) {
        String s = UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        return base + s.substring(0, Math.min(12, s.length()));
    }

    private long topic(String display, String type, String status, String descId, String descEn, double heat,
            Integer featuredOrder) {
        return jdbc.queryForObject("""
                INSERT INTO topics (name_display, name_norm, type, status, desc_id, desc_en, heat_score, featured_order)
                VALUES (?, lower(?), ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, display, display, type, status, descId, descEn, BigDecimal.valueOf(heat),
                featuredOrder);
    }

    private long userTopic(String display) {
        return topic(display, "USER", "ACTIVE", null, null, 0, null);
    }

    private ContentPost post(long authorId, long topicId, String text) {
        ContentPost p = posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of(), null));
        jdbc.update("INSERT INTO content_topics (post_id, topic_id) VALUES (?, ?)", p.getId(), topicId);
        return p;
    }

    private void setCreatedAt(long postId, Instant at) {
        jdbc.update("UPDATE content_posts SET created_at = ? WHERE id = ?", Timestamp.from(at), postId);
    }

    private void like(long postId, long userId, Instant at) {
        jdbc.update("INSERT INTO content_likes (post_id, user_id, created_at) VALUES (?, ?, ?)", postId, userId,
                Timestamp.from(at));
    }

    private long comment(long postId, long authorId, Long parentId, Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO comments (post_id, author_id, parent_id, body, created_at, updated_at,
                                      moderation_status, content_version)
                VALUES (?, ?, ?, 'c', ?, ?, 'VISIBLE', 1) RETURNING id
                """, Long.class, postId, authorId, parentId, Timestamp.from(at), Timestamp.from(at));
    }

    private void hide(long holder, long target, String source) {
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) VALUES (?, ?, ?)", holder, target,
                source);
    }

    private JsonNode getOk(MockHttpServletRequestBuilder req) throws Exception {
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private JsonNode posts(String name, Long viewer, String sort, String cursor, Integer page) throws Exception {
        var req = get("/api/v1/topics/" + name + "/posts");
        if (viewer != null) {
            req = req.header(HttpHeaders.AUTHORIZATION, userBearer(viewer));
        }
        if (sort != null) {
            req = req.param("sort", sort);
        }
        if (cursor != null) {
            req = req.param("cursor", cursor);
        }
        if (page != null) {
            req = req.param("page", String.valueOf(page));
        }
        return getOk(req);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> l = new ArrayList<>();
        page.get("items").forEach(n -> l.add(n.get("id").asLong()));
        return l;
    }

    private static List<String> names(JsonNode arr) {
        List<String> l = new ArrayList<>();
        arr.forEach(n -> l.add(n.get("name").asText()));
        return l;
    }

    // ---------------------------------------------------------------- AC1 详情

    @Test
    void detailOfficialUserHiddenDisabledMissing() throws Exception {
        String off = uniq("Resmi");
        topic(off, "OFFICIAL", "ACTIVE", "Deskripsi ID", "Description EN", 0, null);
        String enOnly = uniq("EnOnly");
        topic(enOnly, "OFFICIAL", "ACTIVE", null, "Only EN", 0, null);
        String usr = uniq("KucingLucu");
        userTopic(usr);
        String hidden = uniq("Hidden");
        topic(hidden, "USER", "HIDDEN", null, null, 0, null);
        String disabled = uniq("Disabled");
        topic(disabled, "USER", "DISABLED", null, null, 0, null);

        JsonNode id = getOk(get("/api/v1/topics/" + off).header("Accept-Language", "id-ID"));
        assertThat(id.get("name").asText()).isEqualTo(off.toLowerCase());
        assertThat(id.get("displayName").asText()).isEqualTo(off);
        assertThat(id.get("official").asBoolean()).isTrue();
        assertThat(id.get("description").asText()).isEqualTo("Deskripsi ID");
        assertThat(id.has("id")).isFalse();
        assertThat(id.has("postCount")).isFalse();
        assertThat(getOk(get("/api/v1/topics/" + off).header("Accept-Language", "en")).get("description").asText())
                .isEqualTo("Description EN");
        assertThat(getOk(get("/api/v1/topics/" + enOnly).header("Accept-Language", "id-ID"))
                .get("description").asText()).isEqualTo("Only EN");

        JsonNode u = getOk(get("/api/v1/topics/" + usr.toLowerCase()));
        assertThat(u.get("official").asBoolean()).isFalse();
        assertThat(u.has("description")).isFalse();
        // 大小写路径等价
        assertThat(getOk(get("/api/v1/topics/" + usr.toUpperCase())).get("name").asText())
                .isEqualTo(usr.toLowerCase());

        getOk(get("/api/v1/topics/" + hidden));
        mvc.perform(get("/api/v1/topics/" + disabled)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/topics/" + uniq("nope"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/topics/a")).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- AC2 latest

    @Test
    void latestOrderEmptyAndPagination() throws Exception {
        String name = uniq("Latest");
        long t = userTopic(name);
        User author = newUser();

        JsonNode empty = posts(name, null, null, null, null);
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("hasMore").asBoolean()).isFalse();
        assertThat(empty.has("rankMode")).isFalse();

        Set<Long> created = new HashSet<>();
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < 23; i++) {
            ContentPost p = post(author.getId(), t, "#" + name + " " + i);
            setCreatedAt(p.getId(), base.plusSeconds(i));
            created.add(p.getId());
        }
        JsonNode p1 = posts(name, null, "latest", null, null);
        assertThat(p1.get("items")).hasSize(20);
        assertThat(p1.get("hasMore").asBoolean()).isTrue();
        String cursor = p1.get("nextCursor").asText();
        JsonNode p2 = posts(name, null, "latest", cursor, null);
        assertThat(p2.get("items")).hasSize(3);
        assertThat(p2.get("hasMore").asBoolean()).isFalse();

        List<Long> all = new ArrayList<>(ids(p1));
        all.addAll(ids(p2));
        assertThat(new HashSet<>(all)).isEqualTo(created);
        assertThat(all).hasSize(23);
        // 时间倒序（created_at 随 id 递增造的，倒序即 id 倒序）
        List<Long> expected = new ArrayList<>(created);
        expected.sort(java.util.Comparator.reverseOrder());
        assertThat(all).isEqualTo(expected);
        assertThat(p1.get("items").get(0).get("topicNames").get(0).asText()).isEqualTo(name.toLowerCase());
    }

    @Test
    void latestVisibilityFilters() throws Exception {
        String name = uniq("Filter");
        long t = userTopic(name);
        User viewer = newUser();
        User other = newUser();
        User author = newUser();
        User hiddenByViewer = newUser();
        User deactivated = newUser();
        User deleted = newUser();
        User blocksViewer = newUser();

        ContentPost ok = post(author.getId(), t, "ok");
        ContentPost priv = post(author.getId(), t, "priv");
        priv.setVisibility(ContentVisibility.PRIVATE);
        posts.save(priv);
        ContentPost soft = post(author.getId(), t, "soft");
        soft.softDelete();
        posts.save(soft);
        ContentPost review = posts.save(ContentPost.pendingReview(author.getId(), ContentType.DAILY, null, "rv",
                List.of(), null, null, "RISK_HIGH"));
        jdbc.update("INSERT INTO content_topics (post_id, topic_id) VALUES (?, ?)", review.getId(), t);
        ContentPost reported = post(author.getId(), t, "reported");
        jdbc.update("INSERT INTO content_reports (post_id, reporter_id, reason_type) VALUES (?, ?, 'OTHER')",
                reported.getId(), viewer.getId());
        ContentPost ph = post(hiddenByViewer.getId(), t, "hidden-author");
        hide(viewer.getId(), hiddenByViewer.getId(), "BLOCK");
        ContentPost pd = post(deactivated.getId(), t, "deactivated");
        User dd = users.findById(deactivated.getId()).orElseThrow();
        dd.deactivate();
        users.save(dd);
        ContentPost pdel = post(deleted.getId(), t, "deleted-author");
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", deleted.getId());
        ContentPost pb = post(blocksViewer.getId(), t, "author-blocks-viewer");
        hide(blocksViewer.getId(), viewer.getId(), "BLOCK");
        ContentPost throttled = post(author.getId(), t, "throttled");
        jdbc.update("INSERT INTO rank_throttles (scope, target_id, duration, created_at, updated_at)"
                + " VALUES ('POST', ?, 'PERMANENT', now(), now())", throttled.getId());

        List<Long> forViewer = ids(posts(name, viewer.getId(), null, null, null));
        assertThat(forViewer).contains(ok.getId(), pb.getId(), throttled.getId())
                .doesNotContain(priv.getId(), soft.getId(), review.getId(), reported.getId(), ph.getId(), pd.getId(),
                        pdel.getId());

        List<Long> forOther = ids(posts(name, other.getId(), null, null, null));
        assertThat(forOther).contains(ok.getId(), reported.getId(), ph.getId())
                .doesNotContain(pd.getId(), pdel.getId());

        List<Long> forGuest = ids(posts(name, null, null, null, null));
        assertThat(forGuest).contains(ok.getId(), reported.getId(), ph.getId(), pb.getId())
                .doesNotContain(pd.getId(), pdel.getId(), priv.getId(), review.getId());
    }

    @Test
    void postsOfInaccessibleTopic404() throws Exception {
        String disabled = uniq("Gone");
        topic(disabled, "USER", "DISABLED", null, null, 0, null);
        mvc.perform(get("/api/v1/topics/" + disabled + "/posts")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/topics/" + uniq("nope") + "/posts")).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- AC2 popular

    @Test
    void popularOrderFollowsCommentWeightAndIgnoresShadowComments() throws Exception {
        String name = uniq("Populer");
        long t = userTopic(name);
        User author = newUser();
        User shadowAuthor = newUser();
        Instant now = Instant.now();
        ContentPost a = post(author.getId(), t, "a"); // 3 赞
        ContentPost b = post(author.getId(), t, "b"); // 1 赞 + 2 评
        ContentPost c = post(shadowAuthor.getId(), t, "c"); // 3 条影子评论 → 0
        for (int i = 0; i < 3; i++) {
            like(a.getId(), newUser().getId(), now);
        }
        like(b.getId(), newUser().getId(), now);
        comment(b.getId(), newUser().getId(), null, now);
        comment(b.getId(), newUser().getId(), null, now);
        User harasser = newUser();
        hide(shadowAuthor.getId(), harasser.getId(), "BLOCK");
        long shadow = comment(c.getId(), harasser.getId(), null, now);
        comment(c.getId(), newUser().getId(), shadow, now); // 影子评论的回复同样不计
        comment(c.getId(), harasser.getId(), null, now);

        Double original = jdbc.queryForObject("SELECT comment_weight FROM feed_rank_config WHERE id = 1",
                Double.class);
        try {
            jdbc.update("UPDATE feed_rank_config SET comment_weight = 2 WHERE id = 1");
            assertThat(ids(posts(name, null, "popular", null, 1))).containsExactly(b.getId(), a.getId(), c.getId());
            jdbc.update("UPDATE feed_rank_config SET comment_weight = 0.5 WHERE id = 1");
            assertThat(ids(posts(name, null, "popular", null, 1))).containsExactly(a.getId(), b.getId(), c.getId());
        } finally {
            jdbc.update("UPDATE feed_rank_config SET comment_weight = ? WHERE id = 1", original);
        }
        JsonNode p = posts(name, null, "popular", null, null);
        assertThat(p.get("nextCursor").isNull() || !p.has("nextCursor")).isTrue();
    }

    @Test
    void popularPagingAndValidation() throws Exception {
        String name = uniq("Paging");
        long t = userTopic(name);
        User author = newUser();
        Set<Long> created = new HashSet<>();
        for (int i = 0; i < 22; i++) {
            created.add(post(author.getId(), t, "p" + i).getId());
        }
        JsonNode p1 = posts(name, null, "popular", null, 1);
        assertThat(p1.get("items")).hasSize(20);
        assertThat(p1.get("hasMore").asBoolean()).isTrue();
        JsonNode p2 = posts(name, null, "popular", null, 2);
        assertThat(p2.get("items")).hasSize(2);
        Set<Long> all = new HashSet<>(ids(p1));
        all.addAll(ids(p2));
        assertThat(all).isEqualTo(created);

        mvc.perform(get("/api/v1/topics/" + name + "/posts").param("sort", "popular").param("page", "51"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/topics/" + name + "/posts").param("sort", "foo"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------------------------------------------------------------- AC3 联想

    @Test
    void suggestionsAuthPrefixAndUnderscore() throws Exception {
        mvc.perform(get("/api/v1/topic-suggestions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/topic-suggestions").header(HttpHeaders.AUTHORIZATION, vetBearer(1L)))
                .andExpect(status().isForbidden());

        String stem = uniq("sg");
        topic(stem + "_lucu", "USER", "ACTIVE", null, null, 5, null);
        topic(stem + "xlucu", "USER", "ACTIVE", null, null, 9, null); // `_` 若当通配会命中它
        topic(stem + "_resmi", "OFFICIAL", "ACTIVE", null, null, 0, null);
        topic(stem + "_hidden", "USER", "HIDDEN", null, null, 99, null);
        topic(stem + "_disabled", "USER", "DISABLED", null, null, 99, null);
        long viewer = newUser().getId();

        JsonNode r = getOk(get("/api/v1/topic-suggestions").param("q", stem.toUpperCase() + "_")
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer)));
        assertThat(names(r)).containsExactly(stem + "_resmi", stem + "_lucu"); // 官方在前 → 热度
        assertThat(r.get(0).get("official").asBoolean()).isTrue();
        assertThat(r.get(0).has("featured")).isFalse();

        JsonNode prefix = getOk(get("/api/v1/topic-suggestions").param("q", stem)
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer)));
        assertThat(names(prefix)).containsExactly(stem + "_resmi", stem + "xlucu", stem + "_lucu");

        assertThat(getOk(get("/api/v1/topic-suggestions").param("q", "kucing lucu")
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer)))).isEmpty();
        JsonNode top = getOk(get("/api/v1/topic-suggestions").header(HttpHeaders.AUTHORIZATION, userBearer(viewer)));
        assertThat(top.size()).isLessThanOrEqualTo(20);
        assertThat(names(top)).doesNotContain(stem + "_hidden", stem + "_disabled");
    }

    // ---------------------------------------------------------------- AC4 首页话题行 / AC5 推荐

    @Test
    void homeRowFeaturedThenHeat() throws Exception {
        // 首页话题行是全局视图：先把其他用例留下的序号与热度清掉（L1 库不回滚）。
        jdbc.update("UPDATE topics SET featured_order = NULL, heat_score = 0");
        assertThat(getOk(get("/api/v1/topics/home-row"))).isEmpty();

        String f2 = uniq("Fdua");
        topic(f2, "USER", "ACTIVE", null, null, 0, 2);
        String f1 = uniq("Fsatu");
        topic(f1, "OFFICIAL", "ACTIVE", null, null, 0, 1);
        String h1 = uniq("Hsatu");
        topic(h1, "USER", "ACTIVE", null, null, 50, null);
        String h2 = uniq("Hdua");
        topic(h2, "USER", "ACTIVE", null, null, 40, null);
        String h3 = uniq("Htiga");
        topic(h3, "USER", "ACTIVE", null, null, 30, null);
        String h4 = uniq("Hempat");
        topic(h4, "USER", "ACTIVE", null, null, 20, null);
        topic(uniq("Zero"), "USER", "ACTIVE", null, null, 0, null);
        topic(uniq("Hid"), "USER", "HIDDEN", null, null, 100, null);
        topic(uniq("Dis"), "USER", "DISABLED", null, null, 100, null);

        JsonNode row = getOk(get("/api/v1/topics/home-row"));
        assertThat(names(row)).containsExactly(f1.toLowerCase(), f2.toLowerCase(), h1.toLowerCase(),
                h2.toLowerCase(), h3.toLowerCase());
        assertThat(row.get(0).get("featured").asBoolean()).isTrue();
        assertThat(row.get(1).get("featured").asBoolean()).isTrue();
        assertThat(row.get(2).get("featured").asBoolean()).isFalse();
        assertThat(row.get(0).get("official").asBoolean()).isTrue();

        // AC5 推荐：排除当前、HIDDEN / DISABLED、热度 0；≤ 10
        JsonNode related = getOk(get("/api/v1/topics/" + h1 + "/related"));
        assertThat(names(related)).contains(h2.toLowerCase(), h3.toLowerCase(), h4.toLowerCase())
                .doesNotContain(h1.toLowerCase());
        assertThat(related.size()).isLessThanOrEqualTo(10);
        related.forEach(n -> assertThat(n.has("featured")).isFalse());
        String dis = uniq("RelDis");
        topic(dis, "USER", "DISABLED", null, null, 0, null);
        mvc.perform(get("/api/v1/topics/" + dis + "/related")).andExpect(status().isNotFound());
        jdbc.update("UPDATE topics SET featured_order = NULL WHERE featured_order IS NOT NULL");
    }

    // ---------------------------------------------------------------- AC6 顶置

    @Test
    void topicPinAndYield() throws Exception {
        String name = uniq("Pinned");
        long t = userTopic(name);
        User author = newUser();
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        List<ContentPost> created = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            ContentPost p = post(author.getId(), t, "p" + i);
            setCreatedAt(p.getId(), base.plusSeconds(i));
            created.add(p);
        }
        ContentPost pinned = created.get(20); // 最新那条
        Instant now = Instant.now();
        pinService.schedule(ContentPin.ofContent(ContentPin.topicSlot(t), pinned.getId(), now.minusSeconds(60),
                now.plusSeconds(3600)));

        JsonNode slot = getOk(get("/api/v1/content-posts/pinned").param("topic", name));
        assertThat(slot.get("pin").get("item").get("id").asLong()).isEqualTo(pinned.getId());
        // 按内部 id 直取 → 空
        assertThat(getOk(get("/api/v1/content-posts/pinned").param("slot", ContentPin.topicSlot(t))).has("pin"))
                .isFalse();

        JsonNode p1 = posts(name, null, "latest", null, null);
        assertThat(ids(p1)).doesNotContain(pinned.getId()).hasSize(20);
        assertThat(p1.get("hasMore").asBoolean()).isFalse();
        assertThat(ids(posts(name, null, "popular", null, 1))).doesNotContain(pinned.getId());

        // 复审 CONFIRMED-1：popular 每页都排除顶置 —— 再造 5 条凑够两页，第 1、2 页无重复、都不含顶置帖
        Set<Long> nonPinned = new HashSet<>();
        created.stream().filter(p -> !p.getId().equals(pinned.getId())).forEach(p -> nonPinned.add(p.getId()));
        for (int i = 0; i < 5; i++) {
            nonPinned.add(post(author.getId(), t, "extra" + i).getId());
        }
        JsonNode pop1 = posts(name, null, "popular", null, 1);
        JsonNode pop2 = posts(name, null, "popular", null, 2);
        assertThat(pop1.get("items")).hasSize(20);
        assertThat(pop1.get("hasMore").asBoolean()).isTrue();
        List<Long> both = new ArrayList<>(ids(pop1));
        both.addAll(ids(pop2));
        assertThat(both).doesNotHaveDuplicates().doesNotContain(pinned.getId());
        assertThat(new HashSet<>(both)).isEqualTo(nonPinned);

        jdbc.update("UPDATE topics SET status = 'DISABLED' WHERE id = ?", t);
        assertThat(getOk(get("/api/v1/content-posts/pinned").param("topic", name)).has("pin")).isFalse();
        // 不带参数仍是首页坑位（与话题坑位无关）
        mvc.perform(get("/api/v1/content-posts/pinned")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- AC7 热度

    @Test
    void heatJobWindowsShadowAndLock() throws Exception {
        String name = uniq("Heat");
        long t = userTopic(name);
        long emptyTopic = topic(uniq("Stale"), "USER", "ACTIVE", null, null, 77, null);
        jdbc.update("UPDATE topics SET post_count = 9 WHERE id = ?", emptyTopic);
        User author = newUser();
        Instant now = Instant.now();

        ContentPost fresh = post(author.getId(), t, "fresh");
        ContentPost old = post(author.getId(), t, "old");
        setCreatedAt(old.getId(), now.minus(10, ChronoUnit.DAYS));
        ContentPost priv = post(author.getId(), t, "priv");
        priv.setVisibility(ContentVisibility.PRIVATE);
        posts.save(priv);

        like(old.getId(), newUser().getId(), now.minus(1, ChronoUnit.DAYS));   // 老帖近 7 天被赞：计
        like(old.getId(), newUser().getId(), now.minus(8, ChronoUnit.DAYS));   // 8 天前：不计
        comment(fresh.getId(), newUser().getId(), null, now);                  // 计
        User harasser = newUser();
        hide(author.getId(), harasser.getId(), "BLOCK");
        long shadow = comment(fresh.getId(), harasser.getId(), null, now);     // 影子：不计
        comment(fresh.getId(), newUser().getId(), shadow, now);                // 影子的回复：不计
        LocalDate today = LocalDate.now(ScheduleWindow.WIB);
        jdbc.update("INSERT INTO content_post_view_days (post_id, view_date, viewer_key) VALUES (?, ?, 'u:1')",
                fresh.getId(), today);
        jdbc.update("INSERT INTO content_post_view_days (post_id, view_date, viewer_key) VALUES (?, ?, 'u:2')",
                fresh.getId(), today.minusDays(6));
        jdbc.update("INSERT INTO content_post_view_days (post_id, view_date, viewer_key) VALUES (?, ?, 'u:3')",
                fresh.getId(), today.minusDays(7));                            // 第 8 个 WIB 日：不计

        Instant updatedBefore = jdbc.queryForObject("SELECT updated_at FROM topics WHERE id = ?", Timestamp.class, t)
                .toInstant();
        TopicHeatJob.Result r = heatJob.recompute();
        assertThat(r.skipped()).isFalse();

        // 1 新帖 + 2 人天 × 0.1 + 1 赞 + 1 评 = 3.2；post_count = 2（PRIVATE 不计）
        assertThat(jdbc.queryForObject("SELECT heat_score FROM topics WHERE id = ?", BigDecimal.class, t))
                .isEqualByComparingTo("3.2");
        assertThat(jdbc.queryForObject("SELECT post_count FROM topics WHERE id = ?", Integer.class, t)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM topics WHERE id = ?", Timestamp.class, t)
                .toInstant()).isEqualTo(updatedBefore);
        // 无数据话题置 0
        assertThat(jdbc.queryForObject("SELECT heat_score FROM topics WHERE id = ?", BigDecimal.class, emptyTopic))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT post_count FROM topics WHERE id = ?", Integer.class, emptyTopic))
                .isZero();

        // 另一事务持锁 → 本轮跳过、值不变
        like(fresh.getId(), newUser().getId(), now);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT pg_advisory_xact_lock(hashtext('topic_heat'))");
                assertThat(heatJob.recompute().skipped()).isTrue();
            } finally {
                c.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT heat_score FROM topics WHERE id = ?", BigDecimal.class, t))
                .isEqualByComparingTo("3.2");
        heatJob.recompute();
        assertThat(jdbc.queryForObject("SELECT heat_score FROM topics WHERE id = ?", BigDecimal.class, t))
                .isEqualByComparingTo("4.2");
    }

    // ---------------------------------------------------------------- AC8 topicNames

    @Test
    void disabledTopicDropsFromTopicNamesHiddenStays() throws Exception {
        String keep = uniq("Keep");
        long tKeep = userTopic(keep);
        String gone = uniq("Gone");
        long tGone = userTopic(gone);
        User author = newUser();
        long viewer = newUser().getId();
        ContentPost p = post(author.getId(), tKeep, "#" + keep + " #" + gone);
        jdbc.update("INSERT INTO content_topics (post_id, topic_id) VALUES (?, ?)", p.getId(), tGone);

        JsonNode page = posts(keep, viewer, null, null, null);
        assertThat(page.get("items").get(0).get("topicNames")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(keep.toLowerCase(), gone.toLowerCase());

        jdbc.update("UPDATE topics SET status = 'HIDDEN' WHERE id = ?", tGone);
        assertThat(posts(keep, viewer, null, null, null).get("items").get(0).get("topicNames"))
                .extracting(JsonNode::asText).contains(gone.toLowerCase());

        jdbc.update("UPDATE topics SET status = 'DISABLED' WHERE id = ?", tGone);
        assertThat(posts(keep, viewer, null, null, null).get("items").get(0).get("topicNames"))
                .extracting(JsonNode::asText).containsExactly(keep.toLowerCase());
        JsonNode detail = getOk(get("/api/v1/content-posts/" + p.getId()).param("countView", "false")
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer)));
        assertThat(detail.get("topicNames")).extracting(JsonNode::asText).containsExactly(keep.toLowerCase());
    }
}
