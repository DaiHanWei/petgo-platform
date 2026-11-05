package com.tailtopia.admin.pin;

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
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentPinRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentPinService;
import com.tailtopia.support.ApiIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.JsonNode;

/**
 * L1 集成（V1.4.2 social-topic · Story 6.2）：后台话题页顶置 G1a / G1b / G2、话题抽屉区块、禁用 / 下架联动、
 * 与 App 端 {@code GET /content-posts/pinned?topic=} 的端到端回归。🔴 需 Docker（postgres + redis），云端不跑。
 */
class AdminTopicPinIntegrationTest extends ApiIntegrationTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdminAccountRepository adminAccounts;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private ContentPinRepository pins;
    @Autowired
    private ContentPinService pinService;

    @BeforeEach
    void clean() {
        pins.deleteAll();
        jdbc.update("UPDATE topics SET featured_order = NULL WHERE featured_order IS NOT NULL");
    }

    // ---------------------------------------------------------------- 夹具

    private Authentication superAdmin() {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "tpin-" + n + "@tailtopia.test", "话题顶置超管", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.SUPER_ADMIN);
        return new TestingAuthenticationToken(principal, null, new ArrayList<>(principal.getAuthorities()));
    }

    private Authentication staffWith(String... permissions) {
        long n = SEQ.incrementAndGet();
        AdminAccount acc = adminAccounts.save(AdminAccount.newSuperAdmin(
                "tpin-staff-" + n + "@tailtopia.test", "话题顶置测试员", "{bcrypt}x"));
        AdminUserDetails principal = new AdminUserDetails(acc.getId(), null, acc.getLarkEmail(),
                acc.getPasswordHash(), AdminAccountType.STAFF);
        List<GrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            auths.add(new SimpleGrantedAuthority(p));
        }
        return new TestingAuthenticationToken(principal, null, auths);
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

    private String norm(long topicId) {
        return jdbc.queryForObject("SELECT name_norm FROM topics WHERE id = ?", String.class, topicId);
    }

    private ContentPost linkedPost(long authorId, long topicId, String text) {
        ContentPost p = posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of()));
        jdbc.update("INSERT INTO content_topics (post_id, topic_id) VALUES (?, ?)", p.getId(), topicId);
        return p;
    }

    private static String wib(Instant t) {
        return LocalDateTime.ofInstant(t, WIB).format(LOCAL);
    }

    /** 一条时间窗包含当前时刻的推广卡（直接走机制，绕开表单的分钟精度）。 */
    private ContentPin livePromo(String slot, String title) {
        Instant now = Instant.now();
        return pinService.schedule(ContentPin.ofPromo(slot, "https://cdn/p.jpg", title, null,
                now.minus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS)));
    }

    private ContentPin futurePromo(String slot, int dayOffset, String title) {
        Instant base = Instant.now().plus(dayOffset, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        return pinService.schedule(ContentPin.ofPromo(slot, "https://cdn/p.jpg", title, null,
                base, base.plus(1, ChronoUnit.HOURS)));
    }

    private MockHttpServletResponse htmxPost(Authentication who, String url, String... params) throws Exception {
        var req = post(url).with(authentication(who)).with(csrf()).header("HX-Request", "true");
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    private String html(Authentication who, String url, boolean htmx) throws Exception {
        var req = get(url).with(authentication(who));
        if (htmx) {
            req = req.header("HX-Request", "true");
        }
        return mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private JsonNode pinned(String topicName) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/content-posts/pinned").param("topic", topicName))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static boolean hasPin(JsonNode slot) {
        return slot.has("pin") && !slot.get("pin").isNull();
    }

    // ---------------------------------------------------------------- G1a

    @Test
    void topicListShowsActiveAndHiddenButNotDisabledWithLivePinAndPendingCount() throws Exception {
        String key = uniq("Gtab");
        long a = topic(key + "Aa", "OFFICIAL", "ACTIVE", 900);
        long h = topic(key + "Bb", "USER", "HIDDEN", 800);
        long d = topic(key + "Cc", "USER", "DISABLED", 999);
        livePromo(ContentPin.topicSlot(a), "PromoLiveNow");
        futurePromo(ContentPin.topicSlot(a), 3, "Pending1");
        futurePromo(ContentPin.topicSlot(a), 5, "Pending2");

        String page = html(superAdmin(), "/admin/content-pins/topics?q=%23" + key, false);

        assertThat(page).contains("#" + key + "Aa").contains("#" + key + "Bb").doesNotContain("#" + key + "Cc");
        assertThat(page).contains("PromoLiveNow").contains("/admin/content-pins/topics/" + a)
                .contains("/admin/content-pins/topics/" + h).doesNotContain("/admin/content-pins/topics/" + d + "\"");
        assertThat(page.indexOf("#" + key + "Aa")).as("热度倒序").isLessThan(page.indexOf("#" + key + "Bb"));
    }

    @Test
    void topicListSearchTreatsPercentLiterallyAndPagesAtTwenty() throws Exception {
        String key = uniq("Gpg");
        for (int i = 0; i < 21; i++) {
            topic(key + "x" + (char) ('a' + i), "USER", "ACTIVE", 100 - i);
        }
        assertThat(html(superAdmin(), "/admin/content-pins/topics?q=%25", false)).contains("empty");
        String p0 = html(superAdmin(), "/admin/content-pins/topics?q=" + key, false);
        String p1 = html(superAdmin(), "/admin/content-pins/topics?q=" + key + "&page=1", true);
        assertThat(p0).contains("page=1");
        assertThat(p1).contains("#" + key + "xu").doesNotContain("#" + key + "xa<");
    }

    // ---------------------------------------------------------------- G1b

    @Test
    void topicSchedulePageIs200ForActiveHidden404ForDisabledOrMissing() throws Exception {
        String key = uniq("Gb");
        long a = topic(key + "Aa", "OFFICIAL", "ACTIVE", 1);
        long h = topic(key + "Bb", "USER", "HIDDEN", 1);
        long d = topic(key + "Cc", "USER", "DISABLED", 1);
        for (int i = 0; i < 21; i++) {
            futurePromo(ContentPin.topicSlot(a), i + 1, "P" + i);
        }

        String page = html(superAdmin(), "/admin/content-pins/topics/" + a, false);
        assertThat(page).contains("crumbs").contains("hx-get=\"/admin/content-pins/topics/" + a + "?status=&amp;page=1\"")
                .doesNotContain("/admin/content-pins?slot=");
        html(superAdmin(), "/admin/content-pins/topics/" + h, false);
        mvc.perform(get("/admin/content-pins/topics/" + d).with(authentication(superAdmin())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/content-pins/topics/987654321").with(authentication(superAdmin())))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- G2

    @Test
    void newDrawerAndPickerAreScopedToTheTopic() throws Exception {
        User author = newUser();
        String key = uniq("Gpk");
        long t = topic(key + "Aa", "OFFICIAL", "ACTIVE", 1);
        long other = topic(key + "Bb", "USER", "ACTIVE", 1);
        ContentPost mine = linkedPost(author.getId(), t, "milik topik " + key);
        ContentPost foreign = linkedPost(author.getId(), other, "topik lain " + key);
        ContentPost loose = posts.save(ContentPost.publish(author.getId(), ContentType.DAILY, null,
                "tanpa topik " + key, List.of()));
        ContentPost priv = linkedPost(author.getId(), t, "privat " + key);
        priv.setVisibility(ContentVisibility.PRIVATE);
        posts.save(priv);
        ContentPost gone = linkedPost(author.getId(), t, "dihapus " + key);
        gone.softDelete();
        posts.save(gone);

        String drawer = html(superAdmin(), "/admin/content-pins/new/drawer?slot=TOPIC_PAGE&topicId=" + t, true);
        assertThat(drawer).contains("pin-slot-line").contains("name=\"topicId\"").contains("value=\"TOPIC_PAGE\"");

        String picked = html(superAdmin(), "/admin/content-pins/pick?q=" + key + "&topicId=" + t, true);
        assertThat(picked).contains("#" + mine.getId() + "<").doesNotContain("#" + foreign.getId() + "<")
                .doesNotContain("#" + loose.getId() + "<").doesNotContain("#" + priv.getId() + "<")
                .doesNotContain("#" + gone.getId() + "<");
        String byId = html(superAdmin(), "/admin/content-pins/pick?q=" + foreign.getId() + "&topicId=" + t, true);
        assertThat(byId).doesNotContain("#" + foreign.getId() + "<");

        long empty = topic(key + "Cc", "USER", "ACTIVE", 1);
        assertThat(html(superAdmin(), "/admin/content-pins/pick?topicId=" + empty, true))
                .contains("pick-list").doesNotContain("pick-card");
    }

    @Test
    void createValidatesSlotTopicAndPostAndIsolatesOverlaps() throws Exception {
        User author = newUser();
        String key = uniq("Gcr");
        long t = topic(key + "Aa", "OFFICIAL", "ACTIVE", 1);
        long t2 = topic(key + "Bb", "USER", "HIDDEN", 1);
        long dis = topic(key + "Cc", "USER", "DISABLED", 1);
        ContentPost foreign = linkedPost(author.getId(), t2, "lain");
        Instant from = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        String s = wib(from);
        String e = wib(from.plus(2, ChronoUnit.HOURS));
        Authentication admin = superAdmin();

        assertThat(htmxPost(admin, "/admin/content-pins", "slot", "TOPIC_PAGE", "topicId", String.valueOf(t),
                "objectType", "CONTENT", "contentId", String.valueOf(foreign.getId()), "startsAt", s, "endsAt", e)
                .getStatus()).isEqualTo(422);
        assertThat(htmxPost(admin, "/admin/content-pins", "slot", "TOPIC:" + t, "objectType", "PROMO",
                "promoImageUrl", "https://cdn/p.jpg", "promoTitle", "x", "startsAt", s, "endsAt", e)
                .getStatus()).isEqualTo(422);
        assertThat(htmxPost(admin, "/admin/content-pins", "slot", "TOPIC_PAGE", "topicId", String.valueOf(dis),
                "objectType", "PROMO", "promoImageUrl", "https://cdn/p.jpg", "promoTitle", "x", "startsAt", s,
                "endsAt", e).getStatus()).isEqualTo(422);
        assertThat(pins.findAll()).isEmpty();

        for (String[] p : List.of(new String[] {"TOPIC_PAGE", String.valueOf(t)},
                new String[] {"TOPIC_PAGE", String.valueOf(t2)}, new String[] {"HOME_FEED", ""})) {
            assertThat(htmxPost(admin, "/admin/content-pins", "slot", p[0], "topicId", p[1], "objectType", "PROMO",
                    "promoImageUrl", "https://cdn/p.jpg", "promoTitle", "Lomba", "startsAt", s, "endsAt", e)
                    .getStatus()).as(p[0] + p[1]).isEqualTo(200);
        }
        assertThat(pins.findAll()).extracting(ContentPin::getSlot)
                .containsExactlyInAnyOrder("TOPIC:" + t, "TOPIC:" + t2, "HOME_FEED");
        // 同话题同时间窗 → 重叠 422
        assertThat(htmxPost(admin, "/admin/content-pins", "slot", "TOPIC_PAGE", "topicId", String.valueOf(t),
                "objectType", "PROMO", "promoImageUrl", "https://cdn/p.jpg", "promoTitle", "Dup", "startsAt", s,
                "endsAt", e).getStatus()).isEqualTo(422);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_audit_logs WHERE action_type = 'CONTENT_PIN_CREATE' AND summary LIKE ?
                """, Integer.class, "slot=TOPIC:" + t + " %")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 话题抽屉（AC8）

    @Test
    void createFromTopicDrawerSwitchesBackToTheTopicDrawer() throws Exception {
        long t = topic(uniq("Gdw") + "Aa", "OFFICIAL", "ACTIVE", 1);
        Instant from = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

        MockHttpServletResponse r = htmxPost(superAdmin(), "/admin/content-pins", "slot", "TOPIC_PAGE",
                "topicId", String.valueOf(t), "from", "topic", "objectType", "PROMO",
                "promoImageUrl", "https://cdn/p.jpg", "promoTitle", "Lomba", "startsAt", wib(from),
                "endsAt", wib(from.plus(1, ChronoUnit.HOURS)));

        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("HX-Trigger")).contains("admin:drawer-open").contains("/admin/topics/" + t + "/drawer");
    }

    @Test
    void topicDrawerBlockShowsOneActiveAndThreePendingAndRespectsPermissions() throws Exception {
        String key = uniq("Gbl");
        long t = topic(key + "Aa", "OFFICIAL", "ACTIVE", 1);
        long dis = topic(key + "Bb", "USER", "DISABLED", 1);
        livePromo(ContentPin.topicSlot(t), "LiveOne");
        for (int i = 1; i <= 5; i++) {
            futurePromo(ContentPin.topicSlot(t), i, "Pend" + i);
        }

        String full = html(superAdmin(), "/admin/topics/" + t + "/drawer", true);
        assertThat(full).contains("data-section=\"topic-pins\"").contains("LiveOne")
                .contains("Pend1").contains("Pend2").contains("Pend3").doesNotContain("Pend4")
                .contains("slot=TOPIC_PAGE");
        assertThat(html(superAdmin(), "/admin/topics/" + dis + "/drawer", true)).doesNotContain("topic-pins");
        assertThat(html(staffWith(AdminPermissions.TOPIC_VIEW), "/admin/topics/" + t + "/drawer", true))
                .doesNotContain("topic-pins");
        String viewOnly = html(staffWith(AdminPermissions.TOPIC_VIEW, AdminPermissions.CONTENT_PIN_VIEW),
                "/admin/topics/" + t + "/drawer", true);
        assertThat(viewOnly).contains("topic-pins").doesNotContain("slot=TOPIC_PAGE");
    }

    // ---------------------------------------------------------------- 联动（AC6 / AC7）与端到端（AC9）

    @Test
    void disablingTheTopicEndsAllItsPinsAndRestoreDoesNotRevive() throws Exception {
        long t = topic(uniq("Gds") + "Aa", "OFFICIAL", "ACTIVE", 1);
        ContentPin live = livePromo(ContentPin.topicSlot(t), "L");
        ContentPin pend = futurePromo(ContentPin.topicSlot(t), 2, "P");
        Authentication admin = superAdmin();

        assertThat(htmxPost(admin, "/admin/topics/" + t + "/disable").getStatus()).isEqualTo(200);
        assertThat(pins.findById(live.getId()).orElseThrow().getTerminatedAt()).isNotNull();
        assertThat(pins.findById(pend.getId()).orElseThrow().getTerminatedAt()).isNotNull();

        assertThat(htmxPost(admin, "/admin/topics/" + t + "/restore").getStatus()).isEqualTo(200);
        assertThat(pins.findById(live.getId()).orElseThrow().getTerminatedAt()).isNotNull();
        assertThat(html(admin, "/admin/content-pins/topics/" + t, false)).contains("row-muted");
    }

    @Test
    void takedownOfThePinnedPostEndsTheTopicPin() throws Exception {
        User author = newUser();
        long t = topic(uniq("Gtk") + "Aa", "OFFICIAL", "ACTIVE", 1);
        ContentPost p = linkedPost(author.getId(), t, "akan dihapus");
        Instant now = Instant.now();
        ContentPin pin = pinService.schedule(ContentPin.ofContent(ContentPin.topicSlot(t), p.getId(),
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS)));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/content-posts/" + p.getId())
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, userBearer(author.getId())))
                .andReturn();

        assertThat(pins.findById(pin.getId()).orElseThrow().getTerminatedAt())
                .as("作者删帖 → ContentUnavailableEvent → 结束话题页排期").isNotNull();
    }

    @Test
    void endToEndPinnedTopicSlotFollowsTheAdminSchedule() throws Exception {
        String key = uniq("Ge");
        long t = topic(key + "Aa", "OFFICIAL", "ACTIVE", 1);
        String name = norm(t);
        assertThat(hasPin(pinned(name))).isFalse();

        futurePromo(ContentPin.topicSlot(t), 2, "Later");
        assertThat(hasPin(pinned(name))).as("窗口在未来 → 空").isFalse();

        ContentPin live = livePromo(ContentPin.topicSlot(t), "NowOn");
        assertThat(pinned(name).get("pin").get("promo").get("title").asText()).isEqualTo("NowOn");

        jdbc.update("UPDATE topics SET status = 'HIDDEN' WHERE id = ?", t);
        assertThat(hasPin(pinned(name))).as("HIDDEN 仍返回").isTrue();

        pinService.terminateNow(live.getId(), Instant.now());
        assertThat(hasPin(pinned(name))).as("提前结束 → 空").isFalse();

        livePromo(ContentPin.topicSlot(t), "Again");
        jdbc.update("UPDATE topics SET status = 'DISABLED', disabled_at = now() WHERE id = ?", t);
        assertThat(hasPin(pinned(name))).as("DISABLED 不返回").isFalse();
    }

    @Test
    void pinnedContentIsNotRepeatedInTheLatestTopicList() throws Exception {
        User author = newUser();
        long t = topic(uniq("Gl") + "Aa", "OFFICIAL", "ACTIVE", 1);
        String name = norm(t);
        ContentPost a = linkedPost(author.getId(), t, "satu");
        ContentPost b = linkedPost(author.getId(), t, "dua");
        Instant now = Instant.now();
        pinService.schedule(ContentPin.ofContent(ContentPin.topicSlot(t), b.getId(),
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS)));

        assertThat(pinned(name).get("pin").get("item").get("id").asLong()).isEqualTo(b.getId());
        JsonNode latest = json.readTree(mvc.perform(get("/api/v1/topics/" + name + "/posts").param("sort", "latest"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        latest.get("items").forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).contains(a.getId()).doesNotContain(b.getId());
    }

    // ---------------------------------------------------------------- 复审 b / c

    @Test
    void bannedAuthorsPostsAreNeitherOfferedNorAcceptedForTopicPins() throws Exception {
        User banned = newUser();
        String key = uniq("Gbn");
        long t = topic(key + "Aa", "OFFICIAL", "ACTIVE", 1);
        ContentPost p = linkedPost(banned.getId(), t, "dari akun diblokir " + key);
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", banned.getId());

        assertThat(html(superAdmin(), "/admin/content-pins/pick?q=" + key + "&topicId=" + t, true))
                .doesNotContain("#" + p.getId() + "<");
        assertThat(html(superAdmin(), "/admin/content-pins/pick?q=" + p.getId() + "&topicId=" + t, true))
                .doesNotContain("#" + p.getId() + "<");
        Instant from = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        assertThat(htmxPost(superAdmin(), "/admin/content-pins", "slot", "TOPIC_PAGE", "topicId", String.valueOf(t),
                "objectType", "CONTENT", "contentId", String.valueOf(p.getId()), "startsAt", wib(from),
                "endsAt", wib(from.plus(1, ChronoUnit.HOURS))).getStatus()).isEqualTo(422);
        assertThat(pins.findAll()).isEmpty();
    }

    @Test
    void nonHtmxTerminateOfAPinOnADisabledTopicGoesBackToTheTopicList() throws Exception {
        long t = topic(uniq("Grd") + "Aa", "USER", "ACTIVE", 1);
        ContentPin pend = futurePromo(ContentPin.topicSlot(t), 2, "P");
        jdbc.update("UPDATE topics SET status = 'DISABLED', disabled_at = now() WHERE id = ?", t);

        String loc = mvc.perform(post("/admin/content-pins/" + pend.getId() + "/terminate")
                        .with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(loc).isEqualTo("/admin/content-pins/topics");
    }
}
