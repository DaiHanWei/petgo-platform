package com.tailtopia.mention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.mention.service.MentionSanitizer;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * L1：@ 候选范围扩大（V1.4.2 social-topic Story 5.1 · FR-157 · D-15）。
 *
 * <p>覆盖 {@code GET /api/v1/me/mention-candidates[?q=]} 的范围 / 排序 / 去重 / 排除 / 上限 / 限流，
 * 以及发帖、评论、回复三处 {@link MentionSanitizer} 的允许集合（互动候选 ∪ 我关注 ∪ 关注我）。
 * <p>🔴 本类同时承担「不是全局用户搜索」的守卫职责：{@link #outOfScopeUsersAreNeverFound}。
 * 共享测试库不回滚：每个用例自造用户，关键词用每次唯一的前缀，范围本身就把别的测试数据挡在外面。
 */
class MentionScopeIntegrationTest extends ApiIntegrationTest {

    private static final String PATH = "/api/v1/me/mention-candidates";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private MentionSanitizer sanitizer;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private PetProfileRepository petProfiles;

    private final Instant now = Instant.now();

    // ===== helpers =====

    private void follow(User follower, User followee, Instant at) {
        follow(follower, followee, at, "USER");
    }

    private void follow(User follower, User followee, Instant at, String source) {
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id, created_at, source) VALUES (?, ?, ?, ?)",
                follower.getId(), followee.getId(), Timestamp.from(at), source);
    }

    private void interacted(User owner, User candidate, Instant at) {
        jdbc.update("INSERT INTO mention_candidates (owner_id, candidate_id, last_interacted_at) VALUES (?, ?, ?)",
                owner.getId(), candidate.getId(), Timestamp.from(at));
    }

    private void nickname(User u, String nick) {
        jdbc.update("UPDATE users SET nickname = ? WHERE id = ?", nick, u.getId());
    }

    /** 每次唯一的短前缀（≤ 8 字，远低于 q 的 20 字上限）。 */
    private static String uniquePrefix() {
        return "Zq" + Long.toString(Math.floorMod(SEQ.incrementAndGet(), 2_000_000_000L), 36);
    }

    private JsonNode items(User viewer, String q) throws Exception {
        MockHttpServletRequestBuilder req = get(PATH).header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId()));
        if (q != null) {
            req = req.param("q", q);
        }
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("items");
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> out = new ArrayList<>();
        items.forEach(n -> out.add(n.get("userId").asLong()));
        return out;
    }

    private static List<String> relations(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(n -> out.add(n.get("relation").asText()));
        return out;
    }

    private static List<Long> idsOf(List<User> users) {
        return users.stream().map(User::getId).collect(Collectors.toList());
    }

    private User userWithPet() {
        User u = newUser();
        petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        return u;
    }

    private List<Long> mentionedOnLatestPost(User author) throws Exception {
        String raw = jdbc.queryForObject("SELECT mentioned_user_ids::text FROM content_posts WHERE id ="
                + " (SELECT max(id) FROM content_posts WHERE author_id = ?)", String.class, author.getId());
        return parseIds(raw);
    }

    private List<Long> mentionedOnLatestComment(User author) throws Exception {
        String raw = jdbc.queryForObject("SELECT mentioned_user_ids::text FROM comments WHERE id ="
                + " (SELECT max(id) FROM comments WHERE author_id = ?)", String.class, author.getId());
        return parseIds(raw);
    }

    private List<Long> parseIds(String raw) {
        List<Long> out = new ArrayList<>();
        if (raw != null) {
            json.readTree(raw).forEach(n -> out.add(n.asLong()));
        }
        return out;
    }

    private static String idsJson(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private long publicPost(User author) {
        return jdbc.queryForObject("INSERT INTO content_posts (author_id, type, status, visibility, created_at,"
                + " updated_at, content_version) VALUES (?, 'DAILY', 'PUBLISHED', 'PUBLIC', now(), now(), 1)"
                + " RETURNING id", Long.class, author.getId());
    }

    // ===== 空集 =====

    @Test
    void brandNewUserGetsAnEmptyList() throws Exception {
        User me = newUser();
        assertThat(items(me, null)).isEmpty();
        assertThat(items(me, "a")).isEmpty();
    }

    // ===== AC1 快捷列表 =====

    @Test
    void quickListPutsFollowingFirstThenFillsWithInteractionsUpToThirty() throws Exception {
        User me = newUser();
        List<User> followed = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            User u = newUser();
            followed.add(u);
            follow(me, u, now.minus(Duration.ofMinutes(i))); // 0 号最新
        }
        List<User> interactedOnly = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            // 前 3 个互动候选与关注重叠（且互动时间比所有人都新）
            User u = i < 3 ? followed.get(i) : newUser();
            interacted(me, u, now.minus(Duration.ofSeconds(i)));
            if (i >= 3) {
                interactedOnly.add(u);
            }
        }
        // 粉丝不进快捷列表
        User fan = newUser();
        follow(fan, me, now);

        JsonNode items = items(me, null);
        List<Long> got = ids(items);

        assertThat(got).hasSize(30).doesNotHaveDuplicates().doesNotContain(fan.getId());
        assertThat(got.subList(0, 10)).containsExactlyElementsOf(idsOf(followed));
        assertThat(got.subList(10, 30)).containsExactlyElementsOf(idsOf(interactedOnly.subList(0, 20)));
        assertThat(relations(items).subList(0, 10)).containsOnly("following");
        assertThat(relations(items).subList(10, 30)).containsOnly("interacted");
    }

    @Test
    void systemFollowedOfficialAccountIsInTheQuickList() throws Exception {
        User me = newUser();
        User official = newUser();
        // 造数与生产形态一致：官方号（role=ADMIN）的 google_sub 必带 admin: 前缀（否则污染 SyntheticAccountParityTest 全表护栏）
        jdbc.update("UPDATE users SET role = 'ADMIN', google_sub = 'admin:' || google_sub WHERE id = ?", official.getId());
        follow(me, official, now, "SYSTEM");

        JsonNode items = items(me, null);
        assertThat(ids(items)).containsExactly(official.getId());
        assertThat(relations(items)).containsExactly("following");
    }

    // ===== AC2 带 q =====

    @Test
    void searchCoversThreeScopesInPriorityOrderAndOutOfScopeIsAbsent() throws Exception {
        String p = uniquePrefix();
        User me = newUser();
        User following = newUser();
        User follower = newUser();
        User interactedU = newUser();
        User stranger = newUser();
        nickname(following, p + "a");
        nickname(follower, p + "b");
        nickname(interactedU, p + "c");
        nickname(stranger, p + "d");
        follow(me, following, now.minus(Duration.ofDays(3)));
        follow(follower, me, now);
        interacted(me, interactedU, now);

        JsonNode items = items(me, p.toLowerCase());
        assertThat(ids(items)).containsExactly(following.getId(), follower.getId(), interactedU.getId());
        assertThat(relations(items)).containsExactly("following", "follower", "interacted");
    }

    @Test
    void outOfScopeUsersAreNeverFound() throws Exception {
        // 🔴 「不是全局用户搜索」的守卫：昵称完全一致、但与我无任何关系 → 搜不到。
        String p = uniquePrefix();
        User me = newUser();
        User stranger = newUser();
        nickname(stranger, p);
        assertThat(items(me, p)).isEmpty();
    }

    @Test
    void mutualFollowAppearsOnceAsFollowing() throws Exception {
        String p = uniquePrefix();
        User me = newUser();
        User both = newUser();
        nickname(both, p + "x");
        follow(me, both, now.minus(Duration.ofDays(1)));
        follow(both, me, now);
        interacted(me, both, now);

        JsonNode items = items(me, p);
        assertThat(ids(items)).containsExactly(both.getId());
        assertThat(relations(items)).containsExactly("following");
    }

    @Test
    void searchIsCaseInsensitiveAndWildcardsAreLiteral() throws Exception {
        String p = uniquePrefix();
        User me = newUser();
        User plain = newUser();
        User percent = newUser();
        User underscore = newUser();
        nickname(plain, p + "AbC");
        nickname(percent, p + "a%z");
        nickname(underscore, p + "a_z");
        for (User u : List.of(plain, percent, underscore)) {
            follow(me, u, now);
        }

        assertThat(ids(items(me, (p + "abc").toUpperCase()))).containsExactly(plain.getId());
        assertThat(ids(items(me, p + "a%"))).containsExactly(percent.getId());
        assertThat(ids(items(me, p + "a_"))).containsExactly(underscore.getId());
    }

    @Test
    void searchIsCappedAtTwenty() throws Exception {
        String p = uniquePrefix();
        User me = newUser();
        for (int i = 0; i < 25; i++) {
            User u = newUser();
            nickname(u, p + i);
            follow(me, u, now.minus(Duration.ofSeconds(i)));
        }
        assertThat(items(me, p)).hasSize(20);
    }

    @Test
    void keywordOverTwentyIs422() throws Exception {
        User me = newUser();
        mvc.perform(get(PATH).param("q", "a".repeat(21))
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void guestIs401() throws Exception {
        mvc.perform(get(PATH).param("q", "a")).andExpect(status().isUnauthorized());
    }

    // ===== AC4 排除 =====

    @Test
    void hiddenEitherWayDeletedAndDeactivatedAreExcludedEverywhere() throws Exception {
        String p = uniquePrefix();
        User me = newUser();
        User ok = newUser();
        User iBlocked = newUser();
        User blockedMe = newUser();
        User iReported = newUser();
        User gone = newUser();
        User banned = newUser();
        List<User> all = List.of(ok, iBlocked, blockedMe, iReported, gone, banned);
        for (User u : all) {
            nickname(u, p + u.getId());
            follow(me, u, now);
        }
        hideService.block(me.getId(), iBlocked.getId());
        hideService.block(blockedMe.getId(), me.getId());
        hideService.hideByReport(me.getId(), iReported.getId());
        // block 会删关注；补回来，确保排除来自隐藏关系而不是「不在范围内」
        for (User u : List.of(iBlocked, blockedMe)) {
            jdbc.update("INSERT INTO user_follows (follower_id, followee_id, created_at) VALUES (?, ?, now())"
                    + " ON CONFLICT DO NOTHING", me.getId(), u.getId());
        }
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", gone.getId());
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", banned.getId());

        assertThat(ids(items(me, null))).containsExactly(ok.getId());
        assertThat(ids(items(me, p))).containsExactly(ok.getId());

        // 封号可逆：重新激活后再出现
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", banned.getId());
        assertThat(ids(items(me, p))).containsExactlyInAnyOrder(ok.getId(), banned.getId());
    }

    @Test
    void selfNeverAppears() throws Exception {
        String p = uniquePrefix();
        User me = newUser();
        User friend = newUser();
        nickname(me, p + "me");
        nickname(friend, p + "fr");
        // 互关：我同时是 friend 的粉丝与被关注者 —— 结果里只有 friend，没有我自己
        // （user_follows / mention_candidates 都有 not-self CHECK，SQL 里的 u.id <> :me 是读侧兜底）。
        follow(me, friend, now);
        follow(friend, me, now);
        assertThat(ids(items(me, p))).containsExactly(friend.getId());
        assertThat(ids(items(me, null))).containsExactly(friend.getId());
    }

    // ===== 限流 =====

    @Test
    void sixtyFirstKeywordRequestInAMinuteIs429() throws Exception {
        User me = newUser();
        redis.delete("rl:mention-search:" + me.getId());
        for (int i = 0; i < 60; i++) {
            items(me, "a");
        }
        mvc.perform(get(PATH).param("q", "a").header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isTooManyRequests());
        // 不带 q 不限流
        items(me, null);
        redis.delete("rl:mention-search:" + me.getId());
    }

    // ===== AC5 发帖 / 评论 / 回复的服务端校验 =====

    @Test
    void publishKeepsOnlyInScopeActiveMentions() throws Exception {
        User me = userWithPet();
        User fan = newUser();
        User followee = newUser();
        User interactedU = newUser();
        User stranger = newUser();
        User banned = newUser();
        follow(fan, me, now);
        follow(me, followee, now);
        interacted(me, interactedU, now);
        follow(me, banned, now);
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", banned.getId());

        List<Long> submitted = List.of(fan.getId(), followee.getId(), interactedU.getId(), stranger.getId(),
                banned.getId());
        mvc.perform(post("/api/v1/content-posts").header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DAILY\",\"text\":\"Halo teman\",\"mentionedUserIds\":"
                                + idsJson(submitted) + "}"))
                .andExpect(status().isCreated());

        assertThat(mentionedOnLatestPost(me))
                .containsExactly(fan.getId(), followee.getId(), interactedU.getId());

        // 发布之后取关不影响已落库的名单
        jdbc.update("DELETE FROM user_follows WHERE follower_id = ? AND followee_id = ?", me.getId(),
                followee.getId());
        assertThat(mentionedOnLatestPost(me))
                .containsExactly(fan.getId(), followee.getId(), interactedU.getId());
    }

    @Test
    void publishWithSixMentionsIs422AndWritesNothing() throws Exception {
        User me = userWithPet();
        List<Long> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            User u = newUser();
            follow(me, u, now);
            six.add(u.getId());
        }
        Long before = jdbc.queryForObject("SELECT count(*) FROM content_posts WHERE author_id = ?", Long.class,
                me.getId());
        mvc.perform(post("/api/v1/content-posts").header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DAILY\",\"text\":\"Halo\",\"mentionedUserIds\":" + idsJson(six) + "}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_posts WHERE author_id = ?", Long.class,
                me.getId())).isEqualTo(before);
    }

    @Test
    void commentAndReplyUseTheSameScope() throws Exception {
        User me = newUser();
        User postAuthor = newUser();
        User fan = newUser();
        User stranger = newUser();
        follow(fan, me, now);
        long postId = publicPost(postAuthor);
        String body = "{\"body\":\"hai\",\"mentionedUserIds\":" + idsJson(List.of(fan.getId(), stranger.getId()))
                + "}";

        mvc.perform(post("/api/v1/content-posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(mentionedOnLatestComment(me)).containsExactly(fan.getId());

        long parentId = jdbc.queryForObject("SELECT max(id) FROM comments WHERE author_id = ?", Long.class,
                me.getId());
        mvc.perform(post("/api/v1/comments/" + parentId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(mentionedOnLatestComment(me)).containsExactly(fan.getId());
    }

    @Test
    void officialAuthorCanMentionAnyAutoFollower() {
        // AC3.2：官方号不特殊处理 —— 自动关注了它的人都是它的粉丝，自然在范围内。
        User official = newUser();
        // 造数与生产形态一致：官方号（role=ADMIN）的 google_sub 必带 admin: 前缀（否则污染 SyntheticAccountParityTest 全表护栏）
        jdbc.update("UPDATE users SET role = 'ADMIN', google_sub = 'admin:' || google_sub WHERE id = ?", official.getId());
        User fan = newUser();
        User stranger = newUser();
        follow(fan, official, now, "SYSTEM");

        assertThat(sanitizer.sanitize(official.getId(), List.of(fan.getId(), stranger.getId())))
                .containsExactly(fan.getId());
    }

    @Test
    void sanitizerStillRejectsSixInService() {
        User me = newUser();
        assertThatThrownBy(() -> sanitizer.sanitize(me.getId(), List.of(1L, 2L, 3L, 4L, 5L, 6L)))
                .isInstanceOf(AppException.class);
    }
}
