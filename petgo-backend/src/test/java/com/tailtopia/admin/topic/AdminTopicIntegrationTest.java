package com.tailtopia.admin.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPinRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.topic.service.TopicHeatJob;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * L1 集成（V1.4.2 Story 6.1 · AB-10B）：话题管理后台 —— MockMvc + 真库（需 Docker postgres + redis，云端不跑）。
 *
 * <p>POST 一律 {@code .with(csrf())}（{@code /admin/**} 过滤链保留 CSRF）；htmx 分支带 {@code HX-Request}。
 * 表是全库共享的：话题名一律带随机后缀，首页话题行每条用例前清空。
 */
class AdminTopicIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentPinRepository pins;
    @Autowired
    private TopicHeatJob heatJob;

    @BeforeEach
    void clearFeaturedRow() {
        jdbc.update("UPDATE topics SET featured_order = NULL WHERE featured_order IS NOT NULL");
    }

    // ---------------------------------------------------------------- 夹具

    private Authentication staffWith(String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "topic-" + n + "@tailtopia.test", "话题测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
    }

    private Authentication manager() {
        return staffWith(AdminPermissions.TOPIC_VIEW, AdminPermissions.TOPIC_MANAGE);
    }

    private static String uniq(String base) {
        String s = UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        return base + s.substring(0, Math.min(10, s.length()));
    }

    private long topic(String display, String type, String status, double heat) {
        return jdbc.queryForObject("""
                INSERT INTO topics (name_display, name_norm, type, status, heat_score)
                VALUES (?, lower(?), ?, ?, ?) RETURNING id
                """, Long.class, display, display, type, status, BigDecimal.valueOf(heat));
    }

    private ContentPost linkedPost(long authorId, long topicId) {
        ContentPost p = posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, "t", List.of(), null));
        jdbc.update("INSERT INTO content_topics (post_id, topic_id) VALUES (?, ?)", p.getId(), topicId);
        return p;
    }

    private String col(long id, String column) {
        return jdbc.queryForObject("SELECT " + column + "::text FROM topics WHERE id = ?", String.class, id);
    }

    private Integer featured(long id) {
        return jdbc.queryForObject("SELECT featured_order FROM topics WHERE id = ?", Integer.class, id);
    }

    private int auditRows(String action, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = ? AND target_id = ?",
                Integer.class, action, String.valueOf(id));
    }

    private int htmxPost(Authentication who, String url, String... params) throws Exception {
        var req = post(url).with(authentication(who)).with(csrf()).header("HX-Request", "true");
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    /**
     * {@code query} 是 URI 模板（MockMvc 会再 encode 一次：直接写 {@code %23} 会变成字面 {@code %23}）；
     * 含 {@code #} / {@code %} 的搜索词走 {@code uriVars}（如 {@code page(m, "?q={q}", "#x")}）才会按原值到达控制器。
     */
    private String page(Authentication who, String query, Object... uriVars) throws Exception {
        return mvc.perform(get("/admin/topics" + query, uriVars).with(authentication(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private JsonNode apiGet(String url, long viewer) throws Exception {
        return json.readTree(mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, userBearer(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> suggestionNames(JsonNode arr) {
        List<String> l = new ArrayList<>();
        arr.forEach(n -> l.add(n.get("name").asText()));
        return l;
    }

    // ---------------------------------------------------------------- 权限（AC1）

    @Test
    void permissionsGateViewAndWrites() throws Exception {
        long id = topic(uniq("Perm"), "USER", "ACTIVE", 1);
        mvc.perform(get("/admin/topics").with(authentication(staffWith(AdminPermissions.CONTENT_VIEW))))
                .andExpect(status().isForbidden());
        Authentication viewer = staffWith(AdminPermissions.TOPIC_VIEW);
        String html = page(viewer, "");
        assertThat(html).doesNotContain("/admin/topics/new/drawer").doesNotContain("data-topic-switch");
        assertThat(htmxPost(viewer, "/admin/topics/" + id + "/hide")).isEqualTo(403);
        assertThat(col(id, "status")).isEqualTo("ACTIVE");
        assertThat(page(manager(), "")).contains("/admin/topics/new/drawer");
    }

    // ---------------------------------------------------------------- 列表（AC2）

    @Test
    void listFiltersSearchEscapesWildcardsAndSummaryIgnoresFilters() throws Exception {
        String base = uniq("Lst");
        long hot = topic(base + "Hot", "OFFICIAL", "ACTIVE", 900);
        long cold = topic(base + "Cold", "USER", "HIDDEN", 1);
        Authentication m = manager();
        String all = page(m, "?q={q}", "#" + base);
        assertThat(all.indexOf(base + "Hot")).isPositive().isLessThan(all.indexOf(base + "Cold"));
        String hiddenOnly = page(m, "?status=HIDDEN&q=" + base);
        assertThat(hiddenOnly).contains(base + "Cold").doesNotContain(base + "Hot");
        assertThat(page(m, "?q={q}", "%")).contains("topics-empty"); // "%" 不当通配
        String summaryAll = all.substring(all.indexOf("id=\"topics-summary\""), all.indexOf("id=\"topics-rows\""));
        String summaryFiltered = hiddenOnly.substring(hiddenOnly.indexOf("id=\"topics-summary\""),
                hiddenOnly.indexOf("id=\"topics-rows\""));
        assertThat(summaryFiltered).isEqualTo(summaryAll);
        assertThat(hot).isNotEqualTo(cold);
    }

    @Test
    void twentyOneRowsPageToASecondPageOfOne() throws Exception {
        String base = uniq("Pg");
        for (int i = 0; i < 21; i++) {
            topic(base + "n" + (char) ('a' + i), "USER", "ACTIVE", 100 - i);
        }
        Authentication m = manager();
        String first = page(m, "?q=" + base);
        assertThat(first.split("id=\"topic-row-", -1)).hasSize(21); // 20 行
        assertThat(first).contains("page=1");
        String second = page(m, "?q=" + base + "&page=1");
        assertThat(second.split("id=\"topic-row-", -1)).hasSize(2); // 1 行
        assertThat(second).contains(base + "nu"); // 热度最低的第 21 条
    }

    // ---------------------------------------------------------------- 新建 / 升级（AC3）

    @Test
    void createNewOfficialTopicAndFocusIt() throws Exception {
        String name = uniq("Baru");
        assertThat(htmxPost(manager(), "/admin/topics", "name", name, "descId", "Halo")).isEqualTo(200);
        Long id = jdbc.queryForObject("SELECT id FROM topics WHERE name_norm = lower(?)", Long.class, name);
        assertThat(col(id, "type")).isEqualTo("OFFICIAL");
        assertThat(page(manager(), "?focus=" + id)).contains("row-flash");
        assertThat(auditRows("TOPIC_CREATED", id)).isEqualTo(1);
    }

    @Test
    void upgradeUserTopicKeepsLinksAndHiddenStatus() throws Exception {
        User author = newUser();
        String name = uniq("Upg");
        long id = topic(name.toLowerCase(), "USER", "HIDDEN", 3);
        linkedPost(author.getId(), id);
        int links = jdbc.queryForObject("SELECT COUNT(*) FROM content_topics WHERE topic_id = ?", Integer.class, id);
        assertThat(htmxPost(manager(), "/admin/topics", "name", name)).isEqualTo(422); // 未确认
        assertThat(htmxPost(manager(), "/admin/topics", "name", name, "upgradeConfirmed", name)).isEqualTo(200);
        assertThat(col(id, "type")).isEqualTo("OFFICIAL");
        assertThat(col(id, "status")).isEqualTo("HIDDEN");
        assertThat(col(id, "name_display")).isEqualTo(name);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_topics WHERE topic_id = ?", Integer.class, id))
                .isEqualTo(links);
        assertThat(auditRows("TOPIC_UPGRADED", id)).isEqualTo(1);
    }

    @Test
    void disabledUserTopicAndOfficialDuplicateAreRejected() throws Exception {
        String dis = uniq("Dis");
        long d = topic(dis, "USER", "DISABLED", 0);
        assertThat(htmxPost(manager(), "/admin/topics", "name", dis, "upgradeConfirmed", dis)).isEqualTo(422);
        assertThat(col(d, "type")).isEqualTo("USER");
        String off = uniq("Off");
        topic(off, "OFFICIAL", "ACTIVE", 0);
        assertThat(htmxPost(manager(), "/admin/topics", "name", off.toUpperCase())).isEqualTo(422);
    }

    @Test
    void concurrentCreateOfSameNameYieldsOneRowAndNo500() throws Exception {
        String name = uniq("Race");
        Authentication m = manager();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            go.await();
            return htmxPost(m, "/admin/topics", "name", name);
        };
        Future<Integer> a = pool.submit(task);
        Future<Integer> b = pool.submit(task);
        go.countDown();
        List<Integer> codes = List.of(a.get(), b.get());
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(200, 422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics WHERE name_norm = lower(?)", Integer.class, name))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- 编辑（AC4）

    @Test
    void editIsCaseOnlyAndAppSeesNewSpelling() throws Exception {
        String lower = uniq("mypet").toLowerCase();
        long id = topic(lower, "USER", "ACTIVE", 1);
        String upper = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        assertThat(htmxPost(manager(), "/admin/topics/" + id + "/edit", "displayName", upper)).isEqualTo(200);
        assertThat(col(id, "name_display")).isEqualTo(upper);
        assertThat(htmxPost(manager(), "/admin/topics/" + id + "/edit", "displayName", upper + "x")).isEqualTo(422);
        String body = mvc.perform(get("/api/v1/topics/" + lower)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(upper);
        assertThat(auditRows("TOPIC_UPDATED", id)).isEqualTo(1); // 422 那次不落审计
    }

    // ---------------------------------------------------------------- 首页话题行（AC5）

    @Test
    void featuredRowLimitShiftSwapAndRange() throws Exception {
        Authentication m = manager();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(topic(uniq("F" + i), "USER", "ACTIVE", i));
        }
        for (int i = 0; i < 5; i++) {
            assertThat(htmxPost(m, "/admin/topics/" + ids.get(i) + "/featured", "on", "true")).isEqualTo(200);
        }
        assertThat(htmxPost(m, "/admin/topics/" + ids.get(5) + "/featured", "on", "true")).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topics WHERE featured_order IS NOT NULL", Integer.class))
                .isEqualTo(5);
        assertThat(htmxPost(m, "/admin/topics/" + ids.get(1) + "/featured", "on", "false")).isEqualTo(200);
        assertThat(List.of(featured(ids.get(0)), featured(ids.get(2)), featured(ids.get(3)), featured(ids.get(4))))
                .containsExactly(1, 2, 3, 4);
        assertThat(htmxPost(m, "/admin/topics/" + ids.get(4) + "/featured-order", "order", "1")).isEqualTo(200);
        assertThat(featured(ids.get(4))).isEqualTo(1);
        assertThat(featured(ids.get(0))).isEqualTo(4);
        assertThat(htmxPost(m, "/admin/topics/" + ids.get(4) + "/featured-order", "order", "5")).isEqualTo(422);
        assertThat(auditRows("TOPIC_UNFEATURED", ids.get(1))).isEqualTo(1);
        assertThat(auditRows("TOPIC_FEATURE_REORDERED", ids.get(4))).isEqualTo(1);
        assertThat(auditRows("TOPIC_FEATURED", ids.get(5))).isZero(); // 满 5 被拒不落审计
    }

    @Test
    void concurrentFeatureKeepsOrdersContiguous() throws Exception {
        Authentication m = manager();
        for (int i = 0; i < 4; i++) {
            long id = topic(uniq("C" + i), "USER", "ACTIVE", 0);
            htmxPost(m, "/admin/topics/" + id + "/featured", "on", "true");
        }
        long x = topic(uniq("X"), "USER", "ACTIVE", 0);
        long y = topic(uniq("Y"), "USER", "ACTIVE", 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<Integer> a = pool.submit(() -> { go.await(); return htmxPost(m, "/admin/topics/" + x + "/featured", "on", "true"); });
        Future<Integer> b = pool.submit(() -> { go.await(); return htmxPost(m, "/admin/topics/" + y + "/featured", "on", "true"); });
        go.countDown();
        assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 422);
        pool.shutdown();
        assertThat(jdbc.queryForList("SELECT featured_order FROM topics WHERE featured_order IS NOT NULL ORDER BY 1",
                Integer.class)).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void concurrentReorderKeepsOrderSetContiguous() throws Exception {
        Authentication m = manager();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long id = topic(uniq("R" + i), "USER", "ACTIVE", 0);
            htmxPost(m, "/admin/topics/" + id + "/featured", "on", "true");
            ids.add(id);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        // 第 4 位 → 1、第 3 位 → 2 同时发生
        Future<Integer> a = pool.submit(() -> { go.await(); return htmxPost(m, "/admin/topics/" + ids.get(3) + "/featured-order", "order", "1"); });
        Future<Integer> b = pool.submit(() -> { go.await(); return htmxPost(m, "/admin/topics/" + ids.get(2) + "/featured-order", "order", "2"); });
        go.countDown();
        List<Integer> codes = List.of(a.get(), b.get());
        pool.shutdown();
        assertThat(codes).allSatisfy(c -> assertThat(c).isIn(200, 422)); // 不 500
        assertThat(jdbc.queryForList("SELECT featured_order FROM topics WHERE featured_order IS NOT NULL ORDER BY 1",
                Integer.class)).containsExactly(1, 2, 3, 4);
    }

    // ---------------------------------------------------------------- 隐藏 / 禁用 / 恢复（AC6）

    @Test
    void hideDisableRestoreLifecycle() throws Exception {
        Authentication m = manager();
        String name = uniq("Life").toLowerCase();
        long id = topic(name, "USER", "ACTIVE", 5);
        htmxPost(m, "/admin/topics/" + id + "/featured", "on", "true");

        User author = newUser();
        long viewer = newUser().getId();
        ContentPost linked = linkedPost(author.getId(), id);
        assertThat(suggestionNames(apiGet("/api/v1/topic-suggestions?q=" + name, viewer))).contains(name);

        assertThat(htmxPost(m, "/admin/topics/" + id + "/hide")).isEqualTo(200);
        assertThat(col(id, "status")).isEqualTo("HIDDEN");
        assertThat(featured(id)).isNull();
        mvc.perform(get("/api/v1/topics/" + name)).andExpect(status().isOk());
        assertThat(suggestionNames(apiGet("/api/v1/topic-suggestions?q=" + name, viewer))).doesNotContain(name);

        Instant now = Instant.now();
        ContentPin live = pins.save(ContentPin.ofPromo(ContentPin.topicSlot(id), "https://x/i.png", "t", "https://x",
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.DAYS)));
        ContentPin future = pins.save(ContentPin.ofPromo(ContentPin.topicSlot(id), "https://x/i.png", "t", "https://x",
                now.plus(2, ChronoUnit.DAYS), now.plus(3, ChronoUnit.DAYS)));
        ContentPin feed = pins.save(ContentPin.ofPromo(ContentPin.SLOT_HOME_FEED, "https://x/i.png", "t", "https://x",
                now.plus(5, ChronoUnit.DAYS), now.plus(6, ChronoUnit.DAYS)));

        assertThat(htmxPost(m, "/admin/topics/" + id + "/disable")).isEqualTo(200);
        assertThat(col(id, "status")).isEqualTo("DISABLED");
        assertThat(col(id, "disabled_at")).isNotNull();
        mvc.perform(get("/api/v1/topics/" + name)).andExpect(status().isNotFound());
        JsonNode detail = apiGet("/api/v1/content-posts/" + linked.getId() + "?countView=false", viewer);
        List<String> topicNames = new ArrayList<>();
        if (detail.has("topicNames")) {
            detail.get("topicNames").forEach(n -> topicNames.add(n.asText()));
        }
        assertThat(topicNames).doesNotContain(name);
        assertThat(suggestionNames(apiGet("/api/v1/topic-suggestions?q=" + name, viewer))).doesNotContain(name);
        assertThat(pins.findById(live.getId()).orElseThrow().getTerminatedAt()).isNotNull();
        assertThat(pins.findById(future.getId()).orElseThrow().getTerminatedAt()).isNotNull();
        assertThat(pins.findById(feed.getId()).orElseThrow().getTerminatedAt()).isNull();

        assertThat(htmxPost(m, "/admin/topics/" + id + "/restore")).isEqualTo(200);
        assertThat(col(id, "status")).isEqualTo("ACTIVE");
        assertThat(col(id, "disabled_at")).isNull();
        assertThat(featured(id)).isNull();
        assertThat(pins.findById(future.getId()).orElseThrow().getTerminatedAt()).isNotNull();
        JsonNode restored = apiGet("/api/v1/content-posts/" + linked.getId() + "?countView=false", viewer);
        assertThat(restored.get("topicNames")).extracting(JsonNode::asText).contains(name); // 关联一直保留
        assertThat(htmxPost(m, "/admin/topics/" + id + "/restore")).isEqualTo(422); // 双击恢复：stale，不重复审计

        for (String action : List.of("TOPIC_FEATURED", "TOPIC_HIDDEN", "TOPIC_DISABLED", "TOPIC_RESTORED")) {
            assertThat(auditRows(action, id)).as(action).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- 帖子数口径（AC7）

    @Test
    void livePostCountMatchesHeatJob() throws Exception {
        User author = newUser();
        long id = topic(uniq("Cnt"), "USER", "ACTIVE", 0);
        linkedPost(author.getId(), id);
        linkedPost(author.getId(), id);
        String drawer = mvc.perform(get("/admin/topics/" + id + "/drawer").with(authentication(manager()))
                .header("HX-Request", "true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        heatJob.recompute();
        String postCount = col(id, "post_count");
        assertThat(drawer).contains("data-live-posts>" + postCount + "<");
    }
}
