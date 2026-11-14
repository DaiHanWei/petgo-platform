package com.tailtopia.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * L1：追更宠物（V1.4.2 social-topic · Story 1.1）—— 需 Docker postgres + redis，云端不跑，待本地验收。
 *
 * <p>覆盖 AC1 迁移 / AC3 追更 / AC4 取消 / AC5 额度 / AC6 拉黑联动 / AC7 注销删宠封号 / AC8 列表 /
 * AC9 访客与主页新字段 / AC12 安全配置。拒绝顺序与额度边界的穷举在 L0 {@code PetFollowServiceTest} /
 * {@code FollowDailyQuotaTest}（集成造 500 行成本高，这里直接 SQL 造当日行）。
 */
class PetFollowIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    /** VET token 须对应库里一条 active vet 行（BannedVetFilter 先查库，查不到 → 401，测不到 403 的角色门）。 */
    @Autowired
    private VetTestSupport vets;

    @Autowired
    private UserHideRelationService hideService;

    @Autowired
    private AccountDeletionService accountDeletion;

    @Autowired
    private AccountDeletionRepository deletions;

    // ===== helpers =====

    private long newPet(User owner) {
        return newPet(owner, "ACTIVE");
    }

    private long newPet(User owner, String status) {
        String token = "it-pf-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO pet_profiles (owner_id, name, pet_type, card_token, status, created_at, updated_at, pet_species, milestone_track) "
                + "VALUES (?, 'Miu', 'CAT', ?, ?, now(), now(), 'CAT', 'CAT')", owner.getId(), token, status);
        return jdbc.queryForObject("SELECT id FROM pet_profiles WHERE card_token = ?", Long.class, token);
    }

    private ResultActions follow(User viewer, long petId) throws Exception {
        return mvc.perform(post("/api/v1/me/pet-follows/" + petId)
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())));
    }

    private ResultActions unfollow(User viewer, long petId) throws Exception {
        return mvc.perform(delete("/api/v1/me/pet-follows/" + petId)
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())));
    }

    private int followerCount(long petId) {
        return jdbc.queryForObject("SELECT follower_count FROM pet_profiles WHERE id = ?", Integer.class, petId);
    }

    private long rows(long followerId) {
        return jdbc.queryForObject("SELECT count(*) FROM pet_follows WHERE follower_id = ?", Long.class, followerId);
    }

    private void deactivate(User u) {
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", u.getId());
    }

    private String tokenOf(User u) {
        return jdbc.queryForObject("SELECT public_token FROM users WHERE id = ?", String.class, u.getId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(MvcResult r) throws Exception {
        return json.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    // ===== AC3 / AC4 =====

    @Test
    void followAndUnfollowCountOnce() throws Exception {
        User me = newUser();
        User owner = newUser();
        long pet = newPet(owner);

        follow(me, pet).andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(true))
                .andExpect(jsonPath("$.followerCount").value(1));
        follow(me, pet).andExpect(status().isOk()).andExpect(jsonPath("$.followerCount").value(1));
        assertThat(followerCount(pet)).isEqualTo(1);

        unfollow(me, pet).andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(false))
                .andExpect(jsonPath("$.followerCount").value(0));
        unfollow(me, pet).andExpect(status().isOk()).andExpect(jsonPath("$.followerCount").value(0));
        assertThat(followerCount(pet)).isZero();
    }

    @Test
    void readOnlyPetCanBeFollowed() throws Exception {
        User me = newUser();
        long pet = newPet(newUser(), "READ_ONLY");
        follow(me, pet).andExpect(status().isOk());
        assertThat(followerCount(pet)).isEqualTo(1);
    }

    @Test
    void rejections() throws Exception {
        User me = newUser();
        User owner = newUser();
        long pet = newPet(owner);
        long mine = newPet(me);

        follow(me, mine).andExpect(status().isUnprocessableEntity());
        follow(me, Long.MAX_VALUE - 1).andExpect(status().isNotFound());

        hideService.block(me.getId(), owner.getId());
        follow(me, pet).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/blocked-user"));
        hideService.unblock(me.getId(), owner.getId());

        deactivate(owner);
        follow(me, pet).andExpect(status().isNotFound());
        assertThat(followerCount(pet)).isZero();
    }

    /** 主人拉黑了我 → 追更照常成功。 */
    @Test
    void ownerBlockedMeStillSucceeds() throws Exception {
        User me = newUser();
        User owner = newUser();
        long pet = newPet(owner);
        hideService.block(owner.getId(), me.getId());

        follow(me, pet).andExpect(status().isOk()).andExpect(jsonPath("$.iFollow").value(true));
    }

    /** 主人封号期间：仍在追更者列表、可取消。 */
    @Test
    void deactivatedOwnerPetStillListedAndUnfollowable() throws Exception {
        User me = newUser();
        User owner = newUser();
        long pet = newPet(owner);
        follow(me, pet).andExpect(status().isOk());
        deactivate(owner);

        mvc.perform(get("/api/v1/users/" + tokenOf(me) + "/pet-follows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].petId").value(pet))
                .andExpect(jsonPath("$.items[0].ownerToken").exists());
        unfollow(me, pet).andExpect(status().isOk());
        assertThat(followerCount(pet)).isZero();
    }

    // ===== AC5 =====

    @Test
    void sharedRateBucketAndDailyQuota() throws Exception {
        User me = newUser();
        // 当日合计 500：关注 250 + 追更 250（直接 SQL 造，绕过限流）
        for (int i = 0; i < 250; i++) {
            User u = newUser();
            jdbc.update("INSERT INTO user_follows (follower_id, followee_id) VALUES (?, ?)", me.getId(), u.getId());
            jdbc.update("INSERT INTO pet_follows (follower_id, pet_profile_id) VALUES (?, ?)", me.getId(), newPet(u));
        }
        follow(me, newPet(newUser())).andExpect(status().isTooManyRequests());
        mvc.perform(put("/api/v1/users/" + tokenOf(newUser()) + "/follow")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void thirtyFirstCallPerMinuteMixedIs429() throws Exception {
        User me = newUser();
        long pet = newPet(newUser());
        String other = tokenOf(newUser());
        for (int i = 0; i < 15; i++) {
            follow(me, pet).andExpect(status().isOk());
            mvc.perform(put("/api/v1/users/" + other + "/follow")
                    .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()))).andExpect(status().isOk());
        }
        follow(me, pet).andExpect(status().isTooManyRequests());
    }

    // ===== AC6 =====

    @Test
    void blockRemovesHolderFollowsOfTargetPetsOnly() throws Exception {
        User a = newUser();
        User b = newUser();
        long b1 = newPet(b);
        long b2 = newPet(b);
        long a1 = newPet(a);
        follow(a, b1).andExpect(status().isOk());
        follow(a, b2).andExpect(status().isOk());
        follow(b, a1).andExpect(status().isOk());

        hideService.block(a.getId(), b.getId());

        assertThat(rows(a.getId())).isZero();
        assertThat(followerCount(b1)).isZero();
        assertThat(followerCount(b2)).isZero();
        assertThat(rows(b.getId())).isEqualTo(1);
        assertThat(followerCount(a1)).isEqualTo(1);

        hideService.block(a.getId(), b.getId()); // 重复拉黑无副作用
        hideService.unblock(a.getId(), b.getId()); // 解除不恢复
        assertThat(rows(a.getId())).isZero();
        hideService.hideByReport(b.getId(), a.getId()); // 举报隐藏不删追更
        assertThat(rows(b.getId())).isEqualTo(1);
    }

    // ===== AC7 =====

    @Test
    void deletingPetCascadesRows() throws Exception {
        User me = newUser();
        long pet = newPet(newUser());
        follow(me, pet).andExpect(status().isOk());
        jdbc.update("DELETE FROM pet_profiles WHERE id = ?", pet);
        assertThat(rows(me.getId())).isZero();
    }

    @Test
    void followerAccountDeletionPurgesRowsAndDecrements() throws Exception {
        User me = newUser();
        long p1 = newPet(newUser());
        long p2 = newPet(newUser());
        follow(me, p1).andExpect(status().isOk());
        follow(me, p2).andExpect(status().isOk());

        AccountDeletion d = deletions.save(AccountDeletion.request(me.getId()));
        accountDeletion.execute(d.getId());

        assertThat(rows(me.getId())).isZero();
        assertThat(followerCount(p1)).isZero();
        assertThat(followerCount(p2)).isZero();
        // 注销后残留 token 再追更 → 401
        follow(me, p1).andExpect(status().isUnauthorized());
    }

    @Test
    void ownerAccountDeletionRemovesPetsAndRows() throws Exception {
        User me = newUser();
        User owner = newUser();
        long pet = newPet(owner);
        follow(me, pet).andExpect(status().isOk());

        AccountDeletion d = deletions.save(AccountDeletion.request(owner.getId()));
        accountDeletion.execute(d.getId());

        assertThat(rows(me.getId())).isZero();
        mvc.perform(get("/api/v1/users/" + tokenOf(me) + "/pet-follows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.petFollowingCount").value(0));
    }

    // ===== AC8 =====

    @Test
    void listPagingWithoutDuplicatesAndCountOnlyOnFirstPage() throws Exception {
        User me = newUser();
        for (int i = 0; i < 25; i++) {
            jdbc.update("INSERT INTO pet_follows (follower_id, pet_profile_id) VALUES (?, ?)", me.getId(),
                    newPet(newUser()));
        }
        String token = tokenOf(me);
        Map<String, Object> first = body(mvc.perform(get("/api/v1/users/" + token + "/pet-follows")
                .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()))).andExpect(status().isOk()).andReturn());
        assertThat(first).containsEntry("hasMore", true);
        assertThat(((Number) first.get("petFollowingCount")).intValue()).isEqualTo(25);
        Map<String, Object> second = body(mvc.perform(get("/api/v1/users/" + token + "/pet-follows")
                        .param("cursor", (String) first.get("nextCursor")))
                .andExpect(status().isOk()).andReturn());
        assertThat(second).containsEntry("hasMore", false).doesNotContainKey("petFollowingCount");

        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> page : List.of(first, second)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
            items.forEach(i -> ids.add(i.get("petId")));
        }
        assertThat(ids).hasSize(25);

        // 坏游标按首页
        mvc.perform(get("/api/v1/users/" + token + "/pet-follows").param("cursor", "!!bad!!"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20));
    }

    @Test
    void emptyListGuestAndBlockedViewer() throws Exception {
        User me = newUser();
        User viewer = newUser();
        mvc.perform(get("/api/v1/users/" + tokenOf(me) + "/pet-follows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.petFollowingCount").value(0));

        long pet = newPet(newUser());
        follow(me, pet).andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/" + tokenOf(me) + "/pet-follows"))
                .andExpect(jsonPath("$.items[0].iFollow").value(false))
                .andExpect(jsonPath("$.items[0].iFollowOwner").value(false));

        hideService.block(viewer.getId(), me.getId());
        mvc.perform(get("/api/v1/users/" + tokenOf(me) + "/pet-follows")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId())))
                .andExpect(status().isForbidden());
    }

    // ===== AC9 =====

    @Test
    void visitorProfileStatsAndPublicProfileCarryNewFields() throws Exception {
        User me = newUser();
        User owner = newUser();
        long pet = newPet(owner);
        follow(me, pet).andExpect(status().isOk());

        mvc.perform(get("/api/v1/pets/" + pet + "/visitor/profile")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(true))
                .andExpect(jsonPath("$.iFollowOwner").value(false))
                .andExpect(jsonPath("$.ownerToken").value(tokenOf(owner)));
        mvc.perform(get("/api/v1/pets/" + pet + "/visitor/stats")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1));
        mvc.perform(get("/api/v1/pet-profiles/me/archive-stats").param("petId", String.valueOf(pet))
                        .header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1));

        // 分享 token 公开路径不含三个新键
        String cardToken = jdbc.queryForObject("SELECT card_token FROM pet_profiles WHERE id = ?", String.class, pet);
        mvc.perform(get("/api/v1/public/shared-pets/" + cardToken + "/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").doesNotExist())
                .andExpect(jsonPath("$.iFollowOwner").doesNotExist())
                .andExpect(jsonPath("$.ownerToken").doesNotExist());

        mvc.perform(get("/api/v1/users/" + me.getId() + "/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.petFollowingCount").value(1));
    }

    // ===== AC12 =====

    @Test
    void vetTokenIs403AndNoTokenIs401() throws Exception {
        long pet = newPet(newUser());
        mvc.perform(post("/api/v1/me/pet-follows/" + pet).header(HttpHeaders.AUTHORIZATION, vetBearer(vets.newActiveVet("pet-follow-it").getId())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/pet-follows/" + pet)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/me/pet-follows/" + pet)).andExpect(status().isUnauthorized());
    }
}
