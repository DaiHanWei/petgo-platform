package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：被追更通知 + 迁移 ⑦（V1.4.2 social-topic · Story 1.2 · AC1 / AC2 / AC5 / AC7）—— 需 Docker postgres + redis，
 * 云端不跑，待本地验收。{@link MockitoBean} {@link NotificationPusher} 计推送次数。
 */
class PetFollowNotifyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserHideRelationService hideService;

    @MockitoBean
    private NotificationPusher pusher;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    // ===== helpers =====

    private long newPet(User owner, String name) {
        long id = MultiPetDbFixture.insertPet(jdbc, owner.getId(), name, "CAT",
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(SEQ.incrementAndGet() % 100_000));
        extraPets.add(id);
        return id;
    }

    private void follow(User viewer, long petId) throws Exception {
        mvc.perform(post("/api/v1/me/pet-follows/" + petId)
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId()))).andExpect(status().isOk());
    }

    private void unfollow(User viewer, long petId) throws Exception {
        mvc.perform(delete("/api/v1/me/pet-follows/" + petId)
                .header(HttpHeaders.AUTHORIZATION, userBearer(viewer.getId()))).andExpect(status().isOk());
    }

    private long count(User u) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'PET_FOLLOWED'",
                Long.class, u.getId());
    }

    private long pushes(User u) {
        return org.mockito.Mockito.mockingDetails(pusher).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("pushToUser"))
                .filter(i -> Long.valueOf(u.getId()).equals(i.getArgument(0)))
                .filter(i -> "PET_FOLLOWED".equals(i.getArgument(3)))
                .count();
    }

    // ===== AC1：迁移 ⑦ =====

    @Test
    void newTypesAndCategoryPassChecksAndOrderAccountStillRejected() {
        User u = newUser();
        for (String type : List.of("PET_FOLLOWED", "PET_DIARY_POSTED")) {
            jdbc.update("INSERT INTO notifications (recipient_user_id, type, title, body, deep_link_type, "
                    + "deep_link_token, target_ref) VALUES (?, ?, 't', 'b', ?, ?, '1')",
                    u.getId(), type, type, "itpf" + SEQ.incrementAndGet());
        }
        jdbc.update("INSERT INTO user_push_mutes (user_id, category) VALUES (?, 'PET_DIARY')", u.getId());
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO user_push_mutes (user_id, category) VALUES (?, 'ORDER_ACCOUNT')", u.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ===== AC2：推送设置 10 项 =====

    @Test
    void pushSettingsHasTenItemsWithPetDiaryEnabledAndLockable() throws Exception {
        User u = newUser();
        mvc.perform(get("/api/v1/me/push-settings").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(10))
                .andExpect(jsonPath("$.categories[4].code").value("PET_DIARY"))
                .andExpect(jsonPath("$.categories[4].enabled").value(true))
                .andExpect(jsonPath("$.categories[4].lockable").value(true));
        mvc.perform(patch("/api/v1/me/push-settings").header(HttpHeaders.AUTHORIZATION, userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":{\"PET_DIARY\":false}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[4].enabled").value(false));
    }

    // ===== AC5 =====

    @Test
    void followNotifiesOwnerOnceWithin24hPerPetAndListRendersPetName() throws Exception {
        User owner = newUser();
        User b = newUser();
        long mochi = newPet(owner, "Mochi");
        long kopi = newPet(owner, "Kopi");

        follow(b, mochi);
        assertThat(count(owner)).isEqualTo(1);
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("PET_FOLLOWED"))
                .andExpect(jsonPath("$.items[0].actorName").value(b.getNickname()))
                .andExpect(jsonPath("$.items[0].actorUserId").value(b.getId()))
                .andExpect(jsonPath("$.items[0].petName").value("Mochi"))
                .andExpect(jsonPath("$.items[0].actorIFollow").doesNotExist());

        unfollow(b, mochi);
        assertThat(count(owner)).as("取消追更无任何消息").isEqualTo(1);
        follow(b, mochi);
        assertThat(count(owner)).as("24h 内再追更不新增").isEqualTo(1);

        follow(b, kopi);
        assertThat(count(owner)).as("另一只宠物另 1 条").isEqualTo(2);
        assertThat(pushes(owner)).isEqualTo(2);
    }

    /** PRD AC9：主人主动拉黑（BLOCK）了 B，B 再追更（接口只拦「我拉黑了主人」，照常成功）→ 主人收不到。 */
    @Test
    void ownerBlockingFollowerSuppressesNotification() throws Exception {
        User owner = newUser();
        User b = newUser();
        long mochi = newPet(owner, "Mochi");
        hideService.block(owner.getId(), b.getId());
        follow(b, mochi);
        assertThat(count(owner)).isZero();
        assertThat(pushes(owner)).isZero();
    }

    /** 主人举报隐藏了 B（不分来源，与拉黑同口径）→ 主人收不到。 */
    @Test
    void ownerReportHidingFollowerSuppressesNotification() throws Exception {
        User owner = newUser();
        User b = newUser();
        long mochi = newPet(owner, "Mochi");
        hideService.hideByReport(owner.getId(), b.getId()); // 主人举报隐藏了追更者（追更本身不被拦）
        follow(b, mochi);
        assertThat(count(owner)).isZero();
    }

    @Test
    void followerCategoryOffDoesNotPushButStillWrites() throws Exception {
        User owner = newUser();
        User b = newUser();
        long mochi = newPet(owner, "Mochi");
        mvc.perform(patch("/api/v1/me/push-settings").header(HttpHeaders.AUTHORIZATION, userBearer(owner.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("categories", Map.of("FOLLOWER", false)))))
                .andExpect(status().isOk());
        follow(b, mochi);
        assertThat(count(owner)).isEqualTo(1);
        assertThat(pushes(owner)).isZero();
    }
}
