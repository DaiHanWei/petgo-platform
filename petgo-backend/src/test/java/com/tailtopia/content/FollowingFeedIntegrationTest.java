package com.tailtopia.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * L1：关注流 {@code GET /api/v1/me/following-feed}（V1.3.4 Following 流 Task 3；
 * V1.4.2 social-topic Story 1.3 合并追更宠物 · D-9 / R-1 / P-2 / P-5）。
 */
class FollowingFeedIntegrationTest extends ApiIntegrationTest {

    private static final String URL = "/api/v1/me/following-feed";

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.tailtopia.support.VetTestSupport vets;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();

    @BeforeEach
    void setUpPets() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
    }

    @AfterEach
    void restorePets() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    private long newPet(User owner, String name) {
        long id = MultiPetDbFixture.insertPet(jdbc, owner.getId(), name, "CAT", Instant.now());
        extraPets.add(id);
        return id;
    }

    private void followPet(User follower, long petId) {
        jdbc.update("INSERT INTO pet_follows (follower_id, pet_profile_id, created_at) VALUES (?, ?, now())",
                follower.getId(), petId);
    }

    /** 关联宠物的帖（Diary = GROWTH_MOMENT），content_pets 显式写好。 */
    private ContentPost savePetPost(long authorId, ContentType type, String text, long... petIds) {
        ContentPost p = posts.save(ContentPost.publish(authorId, type,
                petIds.length == 0 ? null : petIds[0], text, List.of(), null));
        for (long petId : petIds) {
            jdbc.update("INSERT INTO content_pets (post_id, pet_profile_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    p.getId(), petId);
        }
        return p;
    }

    private void block(User holder, User target) {
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) VALUES (?, ?, 'BLOCK')",
                holder.getId(), target.getId());
    }

    private static List<Long> order(JsonNode page) {
        List<Long> l = new ArrayList<>();
        page.get("items").forEach(n -> l.add(n.get("id").asLong()));
        return l;
    }

    private static String reasonOf(JsonNode page, long postId) {
        for (JsonNode n : page.get("items")) {
            if (n.get("id").asLong() == postId) {
                return n.path("reason").asText(null);
            }
        }
        return null;
    }

    private ContentPost savePost(long authorId, String text) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, text, List.of(), null));
    }

    private void follow(long follower, long followee) {
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id) VALUES (?, ?)", follower, followee);
    }

    private JsonNode page(User viewer, String cursor) throws Exception {
        var req = get(URL).header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId()));
        if (cursor != null) {
            req = req.param("cursor", cursor);
        }
        String body = mvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static Set<Long> ids(JsonNode page) {
        Set<Long> s = new HashSet<>();
        page.get("items").forEach(n -> s.add(n.get("id").asLong()));
        return s;
    }

    @Test
    void onlyFollowedAuthorsPublicPostsNewestFirst() throws Exception {
        User viewer = newUser();
        User a = newUser();
        User b = newUser();
        follow(viewer.getId(), a.getId());

        ContentPost older = savePost(a.getId(), "a-old");
        ContentPost priv = savePost(a.getId(), "a-private");
        priv.setVisibility(ContentVisibility.PRIVATE);
        posts.save(priv);
        ContentPost deleted = savePost(a.getId(), "a-deleted");
        deleted.softDelete();
        posts.save(deleted);
        ContentPost newer = savePost(a.getId(), "a-new");
        ContentPost notFollowed = savePost(b.getId(), "b-public");

        JsonNode p = page(viewer, null);

        List<Long> order = new ArrayList<>();
        p.get("items").forEach(n -> order.add(n.get("id").asLong()));
        assertThat(order).containsExactly(newer.getId(), older.getId());
        assertThat(order).doesNotContain(priv.getId(), deleted.getId(), notFollowed.getId());
        assertThat(p.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void excludesReportHiddenAndBannedAuthors() throws Exception {
        User viewer = newUser();
        User c = newUser();
        User d = newUser();
        User ok = newUser();
        follow(viewer.getId(), c.getId());
        follow(viewer.getId(), d.getId());
        follow(viewer.getId(), ok.getId());
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) VALUES (?, ?, 'REPORT')",
                viewer.getId(), c.getId());
        User dd = users.findById(d.getId()).orElseThrow();
        dd.deactivate();
        users.save(dd);

        ContentPost pc = savePost(c.getId(), "c");
        ContentPost pd = savePost(d.getId(), "d");
        ContentPost po = savePost(ok.getId(), "ok");

        Set<Long> got = ids(page(viewer, null));

        assertThat(got).contains(po.getId()).doesNotContain(pc.getId(), pd.getId());
    }

    @Test
    void excludesPostsIReported() throws Exception {
        User viewer = newUser();
        User a = newUser();
        follow(viewer.getId(), a.getId());
        ContentPost reported = savePost(a.getId(), "reported");
        ContentPost fine = savePost(a.getId(), "fine");
        jdbc.update("INSERT INTO content_reports (post_id, reporter_id, reason_type) VALUES (?, ?, 'OTHER')",
                reported.getId(), viewer.getId());

        Set<Long> got = ids(page(viewer, null));

        assertThat(got).containsExactly(fine.getId());
    }

    @Test
    void paginationNoDupNoGap() throws Exception {
        User viewer = newUser();
        User a = newUser();
        follow(viewer.getId(), a.getId());
        Set<Long> created = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            created.add(savePost(a.getId(), "p" + i).getId());
        }
        jdbc.update("UPDATE content_posts SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00' WHERE author_id = ?",
                a.getId());

        JsonNode first = page(viewer, null);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        JsonNode second = page(viewer, first.get("nextCursor").asText());

        List<Long> all = new ArrayList<>();
        first.get("items").forEach(n -> all.add(n.get("id").asLong()));
        second.get("items").forEach(n -> all.add(n.get("id").asLong()));
        assertThat(all).hasSize(25).doesNotHaveDuplicates();
        assertThat(new HashSet<>(all)).isEqualTo(created);
        assertThat(second.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void guestGets401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void vetTokenIs403() throws Exception {
        long vetId = vets.newActiveVet("feed-vet-it").getId();
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, vetBearer(vetId)))
                .andExpect(status().isForbidden());
    }

    // ===== V1.4.2 social-topic Story 1.3 =====

    @Test
    void followedPetPublicDiaryAppearsWithoutFollowingOwner() throws Exception {
        // AC1.3：只追更 Mochi、未关注主人 → 公开 Diary 进流；私密 Diary、主人的 Moment / Tips 不进。
        User viewer = newUser();
        User owner = newUser();
        long mochi = newPet(owner, "Mochi");
        followPet(viewer, mochi);

        ContentPost diary = savePetPost(owner.getId(), ContentType.GROWTH_MOMENT, "diary", mochi);
        ContentPost privDiary = savePetPost(owner.getId(), ContentType.GROWTH_MOMENT, "priv", mochi);
        privDiary.setVisibility(ContentVisibility.PRIVATE);
        posts.save(privDiary);
        ContentPost moment = savePetPost(owner.getId(), ContentType.DAILY, "moment", mochi);
        ContentPost tips = savePost(owner.getId(), "tips");

        JsonNode p = page(viewer, null);

        assertThat(order(p)).containsExactly(diary.getId())
                .doesNotContain(privDiary.getId(), moment.getId(), tips.getId());
        assertThat(reasonOf(p, diary.getId())).isEqualTo("followed_pet");
    }

    @Test
    void readOnlyPetDiaryStillAppears() throws Exception {
        // AC1.5：宠物 READ_ONLY 照常出现。
        User viewer = newUser();
        User owner = newUser();
        long mochi = newPet(owner, "Mochi");
        jdbc.update("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?", mochi);
        followPet(viewer, mochi);
        ContentPost diary = savePetPost(owner.getId(), ContentType.GROWTH_MOMENT, "diary", mochi);

        assertThat(ids(page(viewer, null))).contains(diary.getId());
    }

    @Test
    void postHittingBothPathsOrTwoFollowedPetsAppearsOnce() throws Exception {
        // AC1.4：同时关注主人并追更 Mochi；一帖关联 Mochi 与 Kopi —— 都只出现一次，reason=followed_user。
        User viewer = newUser();
        User owner = newUser();
        long mochi = newPet(owner, "Mochi");
        long kopi = newPet(owner, "Kopi");
        follow(viewer.getId(), owner.getId());
        followPet(viewer, mochi);
        followPet(viewer, kopi);
        ContentPost both = savePetPost(owner.getId(), ContentType.GROWTH_MOMENT, "both", mochi, kopi);

        JsonNode p = page(viewer, null);

        assertThat(order(p)).containsOnlyOnce(both.getId());
        assertThat(reasonOf(p, both.getId())).isEqualTo("followed_user");

        // 只追更两只、不关注主人：双宠帖同样只一次。
        User viewer2 = newUser();
        followPet(viewer2, mochi);
        followPet(viewer2, kopi);
        JsonNode p2 = page(viewer2, null);
        assertThat(order(p2)).containsOnlyOnce(both.getId());
        assertThat(reasonOf(p2, both.getId())).isEqualTo("followed_pet");
    }

    @Test
    void ownerBlockedMeAndINotFollowOwnerHidesPetDiary() throws Exception {
        // AC2.1（R-1）：A 拉黑了 B、B 未关注 A 本人 → A 宠物的 Diary 不出现。
        User b = newUser();
        User a = newUser();
        long mochi = newPet(a, "Mochi");
        followPet(b, mochi);
        block(a, b);
        ContentPost diary = savePetPost(a.getId(), ContentType.GROWTH_MOMENT, "diary", mochi);

        assertThat(ids(page(b, null))).doesNotContain(diary.getId());
    }

    @Test
    void ownerBlockedMeButIStillFollowOwnerKeepsEverything() throws Exception {
        // AC2.2（R-1 组合）：A 拉黑 B（只删 A→B 的关注），B→A 保留 → A 的帖与宠物 Diary 都还在。
        User b = newUser();
        User a = newUser();
        long mochi = newPet(a, "Mochi");
        follow(b.getId(), a.getId());
        followPet(b, mochi);
        block(a, b);
        ContentPost diary = savePetPost(a.getId(), ContentType.GROWTH_MOMENT, "diary", mochi);
        ContentPost moment = savePost(a.getId(), "moment");

        JsonNode p = page(b, null);

        assertThat(ids(p)).contains(diary.getId(), moment.getId());
        assertThat(reasonOf(p, diary.getId())).isEqualTo("followed_user");
    }

    @Test
    void petPathExcludesPostsIReported() throws Exception {
        // AC4.1 四件套 ①（追更一路）：举报过的帖。
        User viewer = newUser();
        User owner = newUser();
        long mochi = newPet(owner, "Mochi");
        followPet(viewer, mochi);
        ContentPost reported = savePetPost(owner.getId(), ContentType.GROWTH_MOMENT, "r", mochi);
        ContentPost fine = savePetPost(owner.getId(), ContentType.GROWTH_MOMENT, "f", mochi);
        jdbc.update("INSERT INTO content_reports (post_id, reporter_id, reason_type) VALUES (?, ?, 'OTHER')",
                reported.getId(), viewer.getId());

        assertThat(ids(page(viewer, null))).contains(fine.getId()).doesNotContain(reported.getId());
    }

    @Test
    void petPathExcludesAuthorIBlocked() throws Exception {
        // AC4.1 四件套 ②a（追更一路）：我拉黑了作者。
        User viewer = newUser();
        User owner = newUser();
        long mochi = newPet(owner, "Mochi");
        followPet(viewer, mochi);
        block(viewer, owner);
        ContentPost diary = savePetPost(owner.getId(), ContentType.GROWTH_MOMENT, "d", mochi);

        assertThat(ids(page(viewer, null))).doesNotContain(diary.getId());
    }

    @Test
    void petPathExcludesAuthorIReportHid() throws Exception {
        // AC4.1 四件套 ②b（追更一路）：我举报隐藏了作者（来源 REPORT）。
        User viewer = newUser();
        User owner = newUser();
        long mochi = newPet(owner, "Mochi");
        followPet(viewer, mochi);
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) VALUES (?, ?, 'REPORT')",
                viewer.getId(), owner.getId());
        ContentPost diary = savePetPost(owner.getId(), ContentType.GROWTH_MOMENT, "d", mochi);

        assertThat(ids(page(viewer, null))).doesNotContain(diary.getId());
    }

    @Test
    void petPathExcludesDeletedAuthor() throws Exception {
        // AC4.1 四件套 ③（追更一路）：作者已注销。
        User viewer = newUser();
        User owner = newUser();
        long mochi = newPet(owner, "Mochi");
        followPet(viewer, mochi);
        ContentPost diary = savePetPost(owner.getId(), ContentType.GROWTH_MOMENT, "d", mochi);
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", owner.getId());

        assertThat(ids(page(viewer, null))).doesNotContain(diary.getId());
    }

    @Test
    void petPathExcludesDeactivatedAuthor() throws Exception {
        // AC4.1 四件套 ④（追更一路）：作者非 ACTIVE。
        User viewer = newUser();
        User owner = newUser();
        long mochi = newPet(owner, "Mochi");
        followPet(viewer, mochi);
        ContentPost diary = savePetPost(owner.getId(), ContentType.GROWTH_MOMENT, "d", mochi);
        User o = users.findById(owner.getId()).orElseThrow();
        o.deactivate();
        users.save(o);

        assertThat(ids(page(viewer, null))).doesNotContain(diary.getId());
    }

    @Test
    void mixedFortyFivePostsPageThreeTimesNoDupNoGapStrictOrder() throws Exception {
        // AC4.2：两路混合 45 条，20 一页翻 3 页；同一 createdAt 以 id 断开。
        User viewer = newUser();
        User followed = newUser();
        User petOwner = newUser();
        follow(viewer.getId(), followed.getId());
        long mochi = newPet(petOwner, "Mochi");
        followPet(viewer, mochi);

        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            ContentPost p = (i % 2 == 0)
                    ? savePost(followed.getId(), "u" + i)
                    : savePetPost(petOwner.getId(), ContentType.GROWTH_MOMENT, "p" + i, mochi);
            created.add(p.getId());
            // 每 3 条共用同一个 createdAt → 靠 id 断开。
            jdbc.update("UPDATE content_posts SET created_at = ? WHERE id = ?",
                    Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i / 3)), p.getId());
        }

        JsonNode first = page(viewer, null);
        JsonNode second = page(viewer, first.get("nextCursor").asText());
        JsonNode third = page(viewer, second.get("nextCursor").asText());

        List<Long> all = new ArrayList<>();
        all.addAll(order(first));
        all.addAll(order(second));
        all.addAll(order(third));
        assertThat(order(first)).hasSize(20);
        assertThat(order(second)).hasSize(20);
        assertThat(order(third)).hasSize(5);
        assertThat(third.get("hasMore").asBoolean()).isFalse();
        assertThat(all).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
        // 严格 createdAt DESC, id DESC：同秒内 id 降序；跨秒按秒降序（插入顺序 = id 升序 = 秒升序）。
        List<Long> expected = new ArrayList<>(created);
        expected.sort((x, y) -> {
            int ix = created.indexOf(x) / 3;
            int iy = created.indexOf(y) / 3;
            return ix != iy ? Integer.compare(iy, ix) : Long.compare(y, x);
        });
        assertThat(all).containsExactlyElementsOf(expected);
        // 第二页起不带两个计数。
        assertThat(second.has("followingUserCount")).isFalse();
        assertThat(second.has("followingPetCount")).isFalse();
    }

    @Test
    void emptyViewerGetsZeroCounts() throws Exception {
        // AC4.3：既无关注也无追更 → items=[]、hasMore=false、两个计数 0。
        User viewer = newUser();
        // 新用户可能被系统自动关注官方号 —— 先清空，模拟「主动取关官方号」后的状态。
        jdbc.update("DELETE FROM user_follows WHERE follower_id = ?", viewer.getId());
        jdbc.update("UPDATE users SET following_count = 0 WHERE id = ?", viewer.getId());

        JsonNode p = page(viewer, null);

        assertThat(p.get("items").size()).isZero();
        assertThat(p.get("hasMore").asBoolean()).isFalse();
        assertThat(p.get("followingUserCount").asLong()).isZero();
        assertThat(p.get("followingPetCount").asLong()).isZero();
    }

    @Test
    void firstPageCountsReflectFollowingCountAndPetFollows() throws Exception {
        // 只追更未关注：followingUserCount 为当前关注数（含系统自动关注的官方号，按实际值断言）、followingPetCount=1。
        User viewer = newUser();
        User owner = newUser();
        long mochi = newPet(owner, "Mochi");
        followPet(viewer, mochi);
        Long followingCount = jdbc.queryForObject("SELECT following_count FROM users WHERE id = ?",
                Long.class, viewer.getId());

        JsonNode p = page(viewer, null);

        assertThat(p.get("followingUserCount").asLong()).isEqualTo(followingCount);
        assertThat(p.get("followingPetCount").asLong()).isEqualTo(1L);
    }

    @Test
    void discoverFeedCarriesNoReasonNorCounts() throws Exception {
        // AC8.2：Discover（GET /content-posts 首页）JSON 不出现 reason / 两个计数键。
        User viewer = newUser();
        savePost(viewer.getId(), "discover");
        String body = mvc.perform(get("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode p = json.readTree(body);

        assertThat(p.has("followingUserCount")).isFalse();
        assertThat(p.has("followingPetCount")).isFalse();
        p.get("items").forEach(n -> assertThat(n.has("reason")).isFalse());
    }
}
