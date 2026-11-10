package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：追更宠物有新 Diary 提醒（V1.4.2 social-topic · Story 1.2 · AC4 / AC6 / AC7）—— 需 Docker postgres + redis，
 * 云端不跑，待本地验收。经真实发帖接口触发（AFTER_COMMIT + {@code @Async}），等业务异步池排空再断言。
 */
class PetDiaryNotifyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserHideRelationService hideService;
    @Autowired
    private ContentService contentService;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private TransactionTemplate tx;
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
    }

    @AfterEach
    void restore() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    // ===== helpers =====

    private long newPet(User owner, String name, Instant createdAt) {
        long id = MultiPetDbFixture.insertPet(jdbc, owner.getId(), name, "CAT", createdAt);
        extraPets.add(id);
        return id;
    }

    private void followPet(User follower, long petId) throws Exception {
        mvc.perform(post("/api/v1/me/pet-follows/" + petId)
                .header(HttpHeaders.AUTHORIZATION, userBearer(follower.getId()))).andExpect(status().isOk());
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
                                + "\"imageUrls\":[\"https://cdn.test/d" + SEQ.incrementAndGet() + ".jpg\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        awaitAsyncIdle();
        return json.readTree(resp).get("id").asLong();
    }

    /** 在一个提交的事务里直接发 {@link ContentPublishedEvent}（AFTER_COMMIT 监听器才会触发），再等异步池排空。 */
    private void publishEvent(User author, ContentType type, long petId) throws InterruptedException {
        tx.executeWithoutResult(st -> events.publishEvent(new ContentPublishedEvent(SEQ.incrementAndGet(),
                author.getId(), type, petId, 1L, ContentVisibility.PUBLIC, Instant.now(), List.of(petId))));
        awaitAsyncIdle();
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

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30);
        }
    }

    private long count(User u) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'PET_DIARY_POSTED'",
                Long.class, u.getId());
    }

    private long logRows(User u) {
        return jdbc.queryForObject("SELECT count(*) FROM pet_diary_notify_log WHERE follower_id = ?", Long.class,
                u.getId());
    }

    private String lastField(User u, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM notifications WHERE recipient_user_id = ? "
                + "AND type = 'PET_DIARY_POSTED' ORDER BY id DESC LIMIT 1", String.class, u.getId());
    }

    private long pushes(User u) {
        return org.mockito.Mockito.mockingDetails(pusher).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("pushToUser"))
                .filter(i -> Long.valueOf(u.getId()).equals(i.getArgument(0)))
                .filter(i -> "PET_DIARY_POSTED".equals(i.getArgument(3)))
                .count();
    }

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    // ===== AC6 =====

    @Test
    void followerOnlyGetsOnePerDayAndTargetRefIsPost() throws Exception {
        User owner = newUser();
        User me = newUser();
        long mochi = newPet(owner, "Mochi", T0);
        followPet(me, mochi);

        long post = publish(owner, "GROWTH_MOMENT", "PUBLIC", mochi);
        await(() -> count(me) == 1);
        assertThat(count(me)).isEqualTo(1);
        assertThat(lastField(me, "target_ref")).isEqualTo(String.valueOf(post));
        assertThat(lastField(me, "variant")).isEqualTo(String.valueOf(mochi));
        assertThat(lastField(me, "title")).isEqualTo("Mochi punya cerita baru");
        assertThat(pushes(me)).isEqualTo(1);

        publish(owner, "GROWTH_MOMENT", "PUBLIC", mochi);
        publish(owner, "GROWTH_MOMENT", "PUBLIC", mochi);
        assertThat(count(me)).as("同日 3 帖只 1 条").isEqualTo(1);
        assertThat(logRows(me)).isEqualTo(1);
    }

    @Test
    void twoFollowedPetsInOnePostGiveOneWithFirstPet() throws Exception {
        User owner = newUser();
        User me = newUser();
        long mochi = newPet(owner, "Mochi", T0);
        long kopi = newPet(owner, "Kopi", T0.plusSeconds(3600));
        followPet(me, mochi);
        followPet(me, kopi);

        publish(owner, "GROWTH_MOMENT", "PUBLIC", kopi, mochi);
        await(() -> count(me) == 1);
        assertThat(count(me)).isEqualTo(1);
        assertThat(lastField(me, "variant")).as("按宠物创建顺序取第一只").isEqualTo(String.valueOf(mochi));
    }

    @Test
    void privateDiaryMomentAndTipsDoNotNotify() throws Exception {
        User owner = newUser();
        User me = newUser();
        long mochi = newPet(owner, "Mochi", T0);
        followPet(me, mochi);

        publish(owner, "GROWTH_MOMENT", "PRIVATE", mochi);
        publish(owner, "DAILY", "PUBLIC", mochi);
        // Tips（KNOWLEDGE）发布受角色限制，直接发事件验监听器的类型闸门
        publishEvent(owner, ContentType.KNOWLEDGE, mochi);
        assertThat(count(me)).isZero();
        assertThat(logRows(me)).isZero();
    }

    /** 追更者拉黑了作者：拉黑联动会删追更行；SQL 补回追更行，单测「追更者隐藏作者」这一道过滤。 */
    @Test
    void followerBlockingAuthorDoesNotReceive() throws Exception {
        User owner = newUser();
        User me = newUser();
        User ok = newUser();
        long mochi = newPet(owner, "Mochi", T0);
        followPet(me, mochi);
        followPet(ok, mochi);
        hideService.block(me.getId(), owner.getId());
        jdbc.update("INSERT INTO pet_follows (follower_id, pet_profile_id, created_at) VALUES (?, ?, now()) "
                + "ON CONFLICT DO NOTHING", me.getId(), mochi);

        publish(owner, "GROWTH_MOMENT", "PUBLIC", mochi);
        await(() -> count(ok) == 1);
        assertThat(count(ok)).isEqualTo(1);
        assertThat(count(me)).isZero();
        assertThat(logRows(me)).isZero();
    }

    /** 作者 DEACTIVATED（封号不能发帖 —— 直接发布事件模拟「发帖提交后作者被封」，验监听器自身的作者有效性闸门）。 */
    @Test
    void deactivatedAuthorSendsNothing() throws Exception {
        User owner = newUser();
        User me = newUser();
        long mochi = newPet(owner, "Mochi", T0);
        followPet(me, mochi);
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", owner.getId());

        publishEvent(owner, ContentType.GROWTH_MOMENT, mochi);
        assertThat(count(me)).isZero();
        assertThat(logRows(me)).isZero();
    }

    @Test
    void authorBlockingFollowerAndDeactivatedFollowerDoNotReceive() throws Exception {
        User owner = newUser();
        User blocked = newUser();
        User deactivated = newUser();
        User ok = newUser();
        long mochi = newPet(owner, "Mochi", T0);
        followPet(blocked, mochi);
        followPet(deactivated, mochi);
        followPet(ok, mochi);
        // 作者拉黑 → 1.1 拉黑联动会删 blocked 的追更行；为单测「BLOCK 口径」这里在拉黑后直接 SQL 补回追更行
        hideService.block(owner.getId(), blocked.getId());
        jdbc.update("INSERT INTO pet_follows (follower_id, pet_profile_id, created_at) VALUES (?, ?, now()) "
                + "ON CONFLICT DO NOTHING", blocked.getId(), mochi);
        jdbc.update("UPDATE users SET status = 'DEACTIVATED' WHERE id = ?", deactivated.getId());

        publish(owner, "GROWTH_MOMENT", "PUBLIC", mochi);
        await(() -> count(ok) == 1);
        assertThat(count(ok)).isEqualTo(1);
        assertThat(count(blocked)).isZero();
        assertThat(count(deactivated)).isZero();
        assertThat(count(owner)).as("作者本人不收").isZero();
    }

    @Test
    void approvingPendingDiaryNotifiesAtApprovalTime() throws Exception {
        User owner = newUser();
        User me = newUser();
        long mochi = newPet(owner, "Mochi", T0);
        followPet(me, mochi);
        long post = publish(owner, "GROWTH_MOMENT", "PUBLIC", mochi);
        await(() -> count(me) == 1);
        // 模拟挂起：翻回 UNDER_REVIEW 并清掉已发的提醒与当日日志，再过审 → 此刻才收
        jdbc.update("UPDATE content_posts SET status = 'UNDER_REVIEW' WHERE id = ?", post);
        jdbc.update("DELETE FROM notifications WHERE recipient_user_id = ? AND type = 'PET_DIARY_POSTED'", me.getId());
        jdbc.update("DELETE FROM pet_diary_notify_log WHERE follower_id = ?", me.getId());

        contentService.approveReview(post);
        awaitAsyncIdle();
        await(() -> count(me) == 1);
        assertThat(count(me)).isEqualTo(1);
    }

    // ===== AC7 =====

    @Test
    void petDiaryCategoryOffStoresButDoesNotPush() throws Exception {
        User owner = newUser();
        User me = newUser();
        long mochi = newPet(owner, "Mochi", T0);
        followPet(me, mochi);
        mvc.perform(patch("/api/v1/me/push-settings").header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":{\"PET_DIARY\":false}}"))
                .andExpect(status().isOk());

        publish(owner, "GROWTH_MOMENT", "PUBLIC", mochi);
        await(() -> count(me) == 1);
        assertThat(count(me)).isEqualTo(1);
        assertThat(pushes(me)).isZero();
    }

    // ===== AC6.8：分批边界 =====

    @Test
    void fanOutOverOneBatchWritesAll() throws Exception {
        User owner = newUser();
        long mochi = newPet(owner, "Mochi", T0);
        List<User> followers = new ArrayList<>();
        for (int i = 0; i < 520; i++) {
            User f = newUser();
            followers.add(f);
            jdbc.update("INSERT INTO pet_follows (follower_id, pet_profile_id, created_at) VALUES (?, ?, now())",
                    f.getId(), mochi);
        }

        publish(owner, "GROWTH_MOMENT", "PUBLIC", mochi);
        await(() -> jdbc.queryForObject("SELECT count(*) FROM pet_diary_notify_log WHERE pet_profile_id = ?",
                Long.class, mochi) == 520L);
        long written = jdbc.queryForObject("SELECT count(*) FROM notifications n WHERE n.type = 'PET_DIARY_POSTED' "
                + "AND n.variant = ?", Long.class, String.valueOf(mochi));
        assertThat(written).isEqualTo(520L);
        assertThat(count(followers.get(0))).isEqualTo(1);
        assertThat(count(followers.get(519))).isEqualTo(1);
    }

    @Test
    void noFollowersWritesNothing() throws Exception {
        User owner = newUser();
        long mochi = newPet(owner, "Mochi", T0);
        publish(owner, "GROWTH_MOMENT", "PUBLIC", mochi);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pet_diary_notify_log WHERE pet_profile_id = ?",
                Long.class, mochi)).isZero();
    }
}
