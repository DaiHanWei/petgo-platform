package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * L1：关注 / 粉丝列表（V1.3.4 follow · Story 2.2 · AC1 / AC8）—— 需 Docker postgres+redis。
 *
 * <p>关注行直接 jdbc 插（可控 created_at，构造同刻多行）；计数列与本测试无关。
 */
class FollowListIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private VetTestSupport vets;

    @Autowired
    private com.tailtopia.social.service.FollowListQueryService lists;

    private void link(User follower, User followee, Instant at) {
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id, created_at) VALUES (?, ?, ?)",
                follower.getId(), followee.getId(), java.sql.Timestamp.from(at));
    }

    private JsonNode page(String path, String bearer, String cursor) throws Exception {
        MockHttpServletRequestBuilder req = get(path);
        if (cursor != null) {
            req = req.param("cursor", cursor);
        }
        if (bearer != null) {
            req = req.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("userId").asLong()));
        return out;
    }

    @Test
    void followingIsNewestFirstAndPagesWithoutLossOrDuplicatesOnSameInstant() throws Exception {
        User owner = newUser();
        Instant same = Instant.parse("2026-10-01T00:00:00Z");
        Set<Long> expected = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            User u = newUser();
            link(owner, u, same);
            expected.add(u.getId());
        }
        String path = "/api/v1/users/" + owner.getPublicToken() + "/following";

        JsonNode p1 = page(path, null, null);
        assertThat(p1.get("items")).hasSize(20);
        assertThat(p1.get("hasMore").asBoolean()).isTrue();
        JsonNode p2 = page(path, null, p1.get("nextCursor").asText());
        assertThat(p2.get("hasMore").asBoolean()).isFalse();

        List<Long> all = new ArrayList<>(ids(p1));
        all.addAll(ids(p2));
        assertThat(all).hasSize(25).doesNotHaveDuplicates();
        assertThat(new HashSet<>(all)).isEqualTo(expected);
        // 同刻组内按对方 id 倒序。
        assertThat(ids(p1)).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void followersAreOrderedByCreatedAtDescAndBadCursorReturnsFirstPage() throws Exception {
        User owner = newUser();
        User older = newUser();
        User newer = newUser();
        link(older, owner, Instant.parse("2026-10-01T00:00:00Z"));
        link(newer, owner, Instant.parse("2026-10-02T00:00:00Z"));
        String path = "/api/v1/users/" + owner.getPublicToken() + "/followers";

        assertThat(ids(page(path, null, null))).containsExactly(newer.getId(), older.getId());
        assertThat(ids(page(path, null, "garbage!!"))).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void relationFieldsAreRelativeToViewer() throws Exception {
        User owner = newUser();
        User viewer = newUser();
        User mutual = newUser();
        User blocked = newUser();
        User reported = newUser();
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        for (User u : List.of(viewer, mutual, blocked, reported)) {
            link(owner, u, t);
        }
        link(viewer, mutual, t);
        link(mutual, viewer, t);
        hideService.block(viewer.getId(), blocked.getId());
        hideService.hideByReport(viewer.getId(), reported.getId());

        JsonNode items = page("/api/v1/users/" + owner.getPublicToken() + "/following",
                userBearer(viewer.getId()), null).get("items");
        // 2.2 code-review F8：先钉住条数与每一行都在，否则下面 if/else 缺行也照样通过。
        assertThat(ids(page("/api/v1/users/" + owner.getPublicToken() + "/following", null, null)))
                .containsExactlyInAnyOrder(viewer.getId(), mutual.getId(), blocked.getId(), reported.getId());
        assertThat(items).hasSize(4);
        for (JsonNode i : items) {
            long id = i.get("userId").asLong();
            if (id == viewer.getId()) {
                assertThat(i.get("self").asBoolean()).isTrue();
            } else if (id == mutual.getId()) {
                assertThat(i.get("iFollow").asBoolean()).isTrue();
                assertThat(i.get("followsMe").asBoolean()).isTrue();
            } else if (id == blocked.getId()) {
                assertThat(i.get("blockedByMe").asBoolean()).isTrue();
            } else if (id == reported.getId()) {
                assertThat(i.get("blockedByMe").asBoolean()).as("举报隐藏不算拉黑（T-20）").isFalse();
            }
            assertThat(i.get("userToken").asText()).hasSize(32);
        }
    }

    @Test
    void guestAndVetSeeAllFalse() throws Exception {
        User owner = newUser();
        User u = newUser();
        link(owner, u, Instant.now());
        link(u, owner, Instant.now());
        String path = "/api/v1/users/" + owner.getPublicToken() + "/following";
        long vetId = vets.newActiveVet("follow-list-it").getId();

        for (String bearer : new String[] {null, vetBearer(vetId)}) {
            JsonNode i = page(path, bearer, null).get("items").get(0);
            assertThat(i.get("iFollow").asBoolean()).isFalse();
            assertThat(i.get("followsMe").asBoolean()).isFalse();
            assertThat(i.get("self").asBoolean()).isFalse();
            assertThat(i.get("blockedByMe").asBoolean()).isFalse();
        }
    }

    @Test
    void deactivatedUsersStayListedButDeactivatedOwnerIs404() throws Exception {
        User owner = newUser();
        User banned = newUser();
        link(owner, banned, Instant.now());
        User b = users.findById(banned.getId()).orElseThrow();
        b.deactivate();
        users.save(b);

        String path = "/api/v1/users/" + owner.getPublicToken() + "/following";
        // 主页用户本人看：他关注着这个封号用户 → 给 token，可取关（D-8）。
        JsonNode items = page(path, userBearer(owner.getId()), null).get("items");
        assertThat(items.get(0).get("userId").asLong()).isEqualTo(banned.getId());
        assertThat(items.get(0).get("userToken").asText()).as("封号可取关，需 token（D-8）").hasSize(32);
        // 2026-10-13 决定：没关注他的人（游客）看 → 照常列出，但不给 token（不显示「Ikuti」，点了必 404）。
        JsonNode guestItems = page(path, null, null).get("items");
        assertThat(guestItems.get(0).get("userId").asLong()).isEqualTo(banned.getId());
        assertThat(guestItems.get(0).has("userToken")).isFalse();

        mvc.perform(get("/api/v1/users/" + banned.getPublicToken() + "/followers"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/users/" + "z".repeat(32) + "/followers"))
                .andExpect(status().isNotFound());
    }

    /** D-10：我拉黑了主页用户 → 403 blocked-user；主页用户拉黑了我 → 照常。 */
    @Test
    void blockRulesFollowTheProfile() throws Exception {
        User owner = newUser();
        User viewer = newUser();
        User other = newUser();
        link(owner, other, Instant.now());
        hideService.block(viewer.getId(), owner.getId());
        mvc.perform(get("/api/v1/users/" + owner.getPublicToken() + "/following")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("blocked-user")));

        User owner2 = newUser();
        link(owner2, other, Instant.now());
        hideService.block(owner2.getId(), viewer.getId());
        assertThat(ids(page("/api/v1/users/" + owner2.getPublicToken() + "/following",
                userBearer(viewer.getId()), null))).containsExactly(other.getId());
    }

    /** I-3：官方号（ADMIN）的关注 / 粉丝列表只给本人；游客与其他用户 403，本人（服务层）200。 */
    @Test
    void officialAccountListsAreClosedExceptToOwner() throws Exception {
        long n = SEQ.incrementAndGet();
        User official = users.save(User.newAdmin("official-list-" + n + "@t.test", "官方" + n, "{bcrypt}x"));
        User fan = newUser();
        link(fan, official, Instant.now());
        link(official, fan, Instant.now());
        for (String suffix : new String[] {"/followers", "/following"}) {
            String path = "/api/v1/users/" + official.getPublicToken() + suffix;
            mvc.perform(get(path)).andExpect(status().isForbidden());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, userBearer(fan.getId())))
                    .andExpect(status().isForbidden());
        }
        // 本人：ADMIN 无法登录 App，故在服务层验证（viewerId == ownerId 放行）。
        assertThat(lists.followers(official.getPublicToken(), official.getId(), null).items()).hasSize(1);
        assertThat(lists.following(official.getPublicToken(), official.getId(), null).items()).hasSize(1);
        // 普通用户的列表不受影响。
        assertThat(page("/api/v1/users/" + fan.getPublicToken() + "/following", null, null).get("items")).hasSize(1);
    }
}
