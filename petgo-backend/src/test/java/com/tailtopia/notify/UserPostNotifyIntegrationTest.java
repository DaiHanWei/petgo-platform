package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.service.FollowUpdatePushQuota;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：关注更新推送（V1.5.0 onboarding-recall Story 5.1 · AC2 ~ AC9）—— 需 Docker postgres + redis，云端不跑，
 * <b>L1 待本地验收</b>。经真实发帖接口触发（AFTER_COMMIT + {@code @Async}），等业务异步池排空再断言。
 * 推送用 {@code @MockitoBean NotificationPusher}（{@code pushToUserNow} 默认返回 false → outcome 记 FAILED，故显式桩 true）。
 */
class UserPostNotifyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FollowUpdatePushQuota quota;
    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    @MockitoBean
    private NotificationPusher pusher;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
        org.mockito.Mockito.when(pusher.pushToUserNow(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    // ===== helpers =====

    private User newAppUser() {
        User u = newUser();
        jdbc.update("UPDATE users SET last_app_version = '1.5.0' WHERE id = ?", u.getId());
        return u;
    }

    private void follow(User follower, User followee) {
        jdbc.update("INSERT INTO user_follows (follower_id, followee_id, created_at) VALUES (?, ?, now()) "
                + "ON CONFLICT DO NOTHING", follower.getId(), followee.getId());
    }

    private long newPet(User owner, String name) {
        long id = MultiPetDbFixture.insertPet(jdbc, owner.getId(), name, "CAT",
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(extraPets.size()));
        extraPets.add(id);
        return id;
    }

    private void followPet(User follower, long petId) throws Exception {
        mvc.perform(post("/api/v1/me/pet-follows/" + petId)
                .header(HttpHeaders.AUTHORIZATION, userBearer(follower.getId()))).andExpect(status().isOk());
        awaitAsyncIdle();
    }

    private long publish(User author, String type, String visibility, long... petIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long p : petIds) {
            ids.append(ids.isEmpty() ? "" : ",").append(p);
        }
        String resp = mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(author.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"petIds\":[" + ids + "],\"text\":\"halo\","
                                + "\"visibility\":\"" + visibility + "\","
                                + "\"imageUrls\":[\"https://cdn.test/u" + SEQ.incrementAndGet() + ".jpg\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        awaitAsyncIdle();
        return json.readTree(resp).get("id").asLong();
    }

    private void awaitAsyncIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        ThreadPoolExecutor pool = asyncExecutor.getThreadPoolExecutor();
        Thread.sleep(50);
        while (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("业务异步池 15s 内未排空");
            }
            Thread.sleep(20);
        }
    }

    private long count(User u, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = ?",
                Long.class, u.getId(), type);
    }

    private List<String> outcomes(User u) {
        return jdbc.queryForList("SELECT push_outcome FROM user_post_notify_log WHERE follower_id = ? "
                + "ORDER BY created_at, post_id", String.class, u.getId());
    }

    private long pushes(User u, String type) {
        return org.mockito.Mockito.mockingDetails(pusher).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("pushToUser"))
                .filter(i -> Long.valueOf(u.getId()).equals(i.getArgument(0)))
                .filter(i -> type.equals(i.getArgument(3)))
                .count();
    }

    // ===== AC2 / AC10 =====

    @Test
    void publicPostNotifiesAndPushesFollowerPrivateDoesNot() throws Exception {
        User b = newUser();
        User a = newAppUser();
        follow(a, b);

        long postId = publish(b, "DAILY", "PUBLIC");
        assertThat(count(a, "USER_POST_PUBLISHED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT target_ref FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'USER_POST_PUBLISHED'", String.class, a.getId())).isEqualTo(String.valueOf(postId));
        assertThat(outcomes(a)).containsExactly("PUSHED");
        assertThat(pushes(a, "USER_POST_PUBLISHED")).isEqualTo(1);

        publish(b, "GROWTH_MOMENT", "PRIVATE", newPet(b, "Mochi"));
        assertThat(count(a, "USER_POST_PUBLISHED")).as("私密帖不发").isEqualTo(1);
    }

    // ===== AC6 =====

    @Test
    void sameAuthorPushesOncePerDayButWritesEveryTime() throws Exception {
        User b = newUser();
        User a = newAppUser();
        follow(a, b);

        publish(b, "DAILY", "PUBLIC");
        publish(b, "DAILY", "PUBLIC");
        publish(b, "DAILY", "PUBLIC");

        assertThat(count(a, "USER_POST_PUBLISHED")).isEqualTo(3);
        assertThat(outcomes(a)).containsExactly("PUSHED", "AUTHOR_DUP", "AUTHOR_DUP");
        assertThat(pushes(a, "USER_POST_PUBLISHED")).isEqualTo(1);
    }

    // ===== AC5 =====

    @Test
    void petFollowerOnlyGetsPetDiary() throws Exception {
        User b = newUser();
        User a = newAppUser();
        follow(a, b);
        long mochi = newPet(b, "Mochi");
        followPet(a, mochi);

        publish(b, "GROWTH_MOMENT", "PUBLIC", mochi);
        assertThat(count(a, "PET_DIARY_POSTED")).isEqualTo(1);
        assertThat(count(a, "USER_POST_PUBLISHED")).isZero();

        publish(b, "GROWTH_MOMENT", "PUBLIC", mochi);
        assertThat(count(a, "PET_DIARY_POSTED")).as("今天已提醒过 Mochi").isEqualTo(1);
        assertThat(count(a, "USER_POST_PUBLISHED")).as("也不改发关注更新").isZero();
    }

    // ===== AC3 / AC4 =====

    @Test
    void blockedHiddenAndInactiveRecipientsExcluded() throws Exception {
        User b = newUser();
        User hides = newAppUser();
        User blocked = newAppUser();
        User ok = newAppUser();
        follow(hides, b);
        follow(blocked, b);
        follow(ok, b);
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) "
                + "VALUES (?, ?, 'BLOCK')", hides.getId(), b.getId());
        jdbc.update("INSERT INTO user_hide_relations (holder_id, target_id, source) "
                + "VALUES (?, ?, 'BLOCK')", b.getId(), blocked.getId());

        publish(b, "DAILY", "PUBLIC");

        assertThat(count(hides, "USER_POST_PUBLISHED")).isZero();
        assertThat(count(blocked, "USER_POST_PUBLISHED")).isZero();
        assertThat(count(ok, "USER_POST_PUBLISHED")).isEqualTo(1);
    }

    // ===== AC7 =====

    @Test
    void dailyCapAcrossTwoKindsIsThree() throws Exception {
        User a = newAppUser();
        List<User> authors = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            User b = newUser();
            follow(a, b);
            authors.add(b);
        }
        for (User b : authors) {
            publish(b, "DAILY", "PUBLIC");
        }
        assertThat(count(a, "USER_POST_PUBLISHED")).isEqualTo(4);
        assertThat(outcomes(a)).containsExactly("PUSHED", "PUSHED", "PUSHED", "CAP_REACHED");

        User c = newUser();
        long kopi = newPet(c, "Kopi");
        followPet(a, kopi);
        publish(c, "GROWTH_MOMENT", "PUBLIC", kopi);
        assertThat(count(a, "PET_DIARY_POSTED")).as("追更通知照写").isEqualTo(1);
        assertThat(pushes(a, "PET_DIARY_POSTED")).as("名额满不推").isZero();
    }

    @Test
    void quotaClaimIsAtomicUnderConcurrency() throws Exception {
        User a = newUser();
        LocalDate d = LocalDate.of(2026, 11, 20);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tasks.add(() -> quota.tryClaim(a.getId(), d));
            }
            long ok = 0;
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                ok += f.get() ? 1 : 0;
            }
            assertThat(ok).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    // ===== AC8 / AC9 =====

    @Test
    void mutedAndOldAppStillWriteButDoNotPush() throws Exception {
        User b = newUser();
        User muted = newAppUser();
        User old = newUser(); // 从未上报版本 → 旧版
        follow(muted, b);
        follow(old, b);
        jdbc.update("INSERT INTO user_push_mutes (user_id, category) VALUES (?, 'PET_DIARY')", muted.getId());

        publish(b, "DAILY", "PUBLIC");

        assertThat(count(muted, "USER_POST_PUBLISHED")).isEqualTo(1);
        assertThat(outcomes(muted)).containsExactly("MUTED");
        assertThat(count(old, "USER_POST_PUBLISHED")).isEqualTo(1);
        assertThat(outcomes(old)).containsExactly("OLD_APP");
        assertThat(pushes(muted, "USER_POST_PUBLISHED") + pushes(old, "USER_POST_PUBLISHED")).isZero();
    }
}
