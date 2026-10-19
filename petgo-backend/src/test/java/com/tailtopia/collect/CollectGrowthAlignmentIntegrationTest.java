package com.tailtopia.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.profile.support.MultiPetDbFixture;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Array;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：V1.4.0 push-collect Story 3.1 · AC7（PRD AC11 / 11a / 11b / 11c）—— ④ 收藏 × ① 被收藏成长值端到端（需 Docker
 * postgres + redis）。
 *
 * <p>④ 本线不写任何成长值逻辑：收藏 / 取消经真实接口 → {@code CollectService} 事务内发事件 → ① {@code GrowthActivityListener}
 * （{@code @Async} AFTER_COMMIT）入账 / 扣回。断言前排空业务异步池（写法同 {@code MyCollectionsEndpointTest}）。
 * Mock 集合与 {@code PushGateIntegrationTest} 相同（只 mock {@link NotificationPusher}），共用缓存上下文。
 */
class CollectGrowthAlignmentIntegrationTest extends ApiIntegrationTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private PlaceRepository places;
    @Autowired
    private ContentService contentService;
    @Autowired
    private UserHideRelationService hideService;
    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    @MockitoBean
    private NotificationPusher pusher;

    private boolean dropped;
    private final List<Long> extraPets = new ArrayList<>();

    @BeforeEach
    void dropOwnerUnique() {
        dropped = MultiPetDbFixture.dropOwnerUniqueIfPresent(jdbc);
    }

    @AfterEach
    void restoreOwnerUnique() {
        MultiPetDbFixture.restoreOwnerUnique(jdbc, dropped, extraPets);
    }

    // ---------- helpers ----------

    private void awaitAsyncIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ThreadPoolExecutor pool = asyncExecutor.getThreadPoolExecutor();
        while (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("业务异步池 10s 内未排空");
            }
            Thread.sleep(20);
        }
    }

    private long postBy(User author) {
        return posts.saveAndFlush(ContentPost.publish(author.getId(), ContentType.DAILY, null, "x",
                List.of("https://cdn.test/x.jpg"))).getId();
    }

    private void collect(User who, long postId) throws Exception {
        mvc.perform(post("/api/v1/content-posts/" + postId + "/collect")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(who.getId())))
                .andExpect(status().is2xxSuccessful());
        awaitAsyncIdle();
    }

    private void uncollect(User who, long postId) throws Exception {
        mvc.perform(delete("/api/v1/content-posts/" + postId + "/collect")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(who.getId())))
                .andExpect(status().is2xxSuccessful());
        awaitAsyncIdle();
    }

    private List<Map<String, Object>> collectRows(User author) {
        return jdbc.queryForList("SELECT id, delta, idem_key, reverses_id, reason FROM growth_ledger "
                + "WHERE user_id = ? AND rule_code = 'COLLECT_RECEIVED' ORDER BY id", author.getId());
    }

    private long collectSum(User author) {
        return jdbc.queryForObject("SELECT COALESCE(sum(delta), 0) FROM growth_ledger WHERE user_id = ? "
                + "AND rule_code = 'COLLECT_RECEIVED'", Long.class, author.getId());
    }

    private long lifetime(User u) {
        List<Long> v = jdbc.queryForList("SELECT lifetime_total FROM growth_accounts WHERE user_id = ?", Long.class,
                u.getId());
        return v.isEmpty() ? 0 : v.get(0);
    }

    private long notifications(User u) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", Long.class,
                u.getId());
    }

    private void setPassiveUsed(User u, int used) {
        jdbc.update("INSERT INTO growth_daily_counters (user_id, wib_date, counter_key, used) VALUES (?, ?, 'PASSIVE', ?) "
                + "ON CONFLICT (user_id, wib_date, counter_key) DO UPDATE SET used = EXCLUDED.used",
                u.getId(), LocalDate.now(WIB), used);
    }

    private int passiveUsed(User u) {
        List<Integer> v = jdbc.queryForList("SELECT used FROM growth_daily_counters WHERE user_id = ? AND wib_date = ? "
                + "AND counter_key = 'PASSIVE'", Integer.class, u.getId(), LocalDate.now(WIB));
        return v.isEmpty() ? 0 : v.get(0);
    }

    // ---------- AC7.1 ----------

    @Test
    @DisplayName("AC11：A 经接口收藏 B 的双宠 Diary → B +2、幂等键 COLLECT_RECEIVED:{A}:{P}、pet_ids = 两只；B 无通知、不推")
    void collectAccruesTwoWithAllLinkedPetsAndNoNotice() throws Exception {
        User b = newUser();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        long petA = MultiPetDbFixture.insertPet(jdbc, b.getId(), "Aaa", "CAT", t0);
        long petB = MultiPetDbFixture.insertPet(jdbc, b.getId(), "Bbb", "DOG", t0.plusSeconds(3600));
        extraPets.add(petA);
        extraPets.add(petB);
        String resp = mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(b.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"GROWTH_MOMENT\",\"petIds\":[" + petB + "," + petA
                                + "],\"text\":\"berdua\",\"visibility\":\"PUBLIC\","
                                + "\"imageUrls\":[\"https://cdn.test/berdua.jpg\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long postId = json.readTree(resp).get("id").asLong();
        awaitAsyncIdle();
        long notificationsBefore = notifications(b);
        User a = newUser();

        collect(a, postId);

        List<Map<String, Object>> rows = collectRows(b);
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("delta")).intValue()).isEqualTo(2);
        assertThat(rows.get(0).get("idem_key")).isEqualTo("COLLECT_RECEIVED:" + a.getId() + ":" + postId);
        List<Long> petIds = jdbc.queryForObject("SELECT pet_ids FROM growth_ledger WHERE user_id = ? "
                + "AND rule_code = 'COLLECT_RECEIVED'", (rs, i) -> {
                    Array arr = rs.getArray(1);
                    return arr == null ? null : Arrays.stream((Long[]) arr.getArray()).toList();
                }, b.getId());
        assertThat(petIds).containsExactlyInAnyOrder(petA, petB);
        assertThat(notifications(b)).isEqualTo(notificationsBefore);
        verify(pusher, never()).pushToUser(eq(b.getId()), any(), any(), any(), any(), any());
        verify(pusher, never()).pushToUserNow(eq(b.getId()), any(), any(), any(), any(), any());
    }

    // ---------- AC7.2 ----------

    @Test
    @DisplayName("被动 50 上限：已用 50 → 无流水、lifetime 不变；已用 49 → +1 截断，取消扣回 −1")
    void passiveCapTruncatesAndReversalMirrorsOriginal() throws Exception {
        User full = newUser();
        long p1 = postBy(full);
        setPassiveUsed(full, 50);
        long lifetimeBefore = lifetime(full);
        collect(newUser(), p1);
        assertThat(collectRows(full)).isEmpty();
        assertThat(lifetime(full)).isEqualTo(lifetimeBefore);
        assertThat(passiveUsed(full)).isEqualTo(50);

        User almost = newUser();
        long p2 = postBy(almost);
        setPassiveUsed(almost, 49);
        User c = newUser();
        collect(c, p2);
        assertThat(collectRows(almost)).extracting(r -> ((Number) r.get("delta")).intValue()).containsExactly(1);
        assertThat(passiveUsed(almost)).isEqualTo(50);

        uncollect(c, p2);
        assertThat(collectRows(almost)).extracting(r -> ((Number) r.get("delta")).intValue()).containsExactly(1, -1);
        assertThat(collectSum(almost)).isZero();
    }

    // ---------- AC7.3 ----------

    @Test
    @DisplayName("AC11a：B 收藏自己的帖 → 不入账、不占额度")
    void selfCollectAccruesNothing() throws Exception {
        User b = newUser();
        long p = postBy(b);

        collect(b, p);

        assertThat(collectRows(b)).isEmpty();
        assertThat(passiveUsed(b)).isZero();
    }

    // ---------- AC7.4 ----------

    @Test
    @DisplayName("AC11b：收藏 → 取消 → 再收藏 = +2 / −2（UNCOLLECT、reverses_id 指原流水）/ 不再入账，净 0；收藏场所无流水")
    void collectUncollectRecollectNetsZeroAndPlaceCollectHasNoEvent() throws Exception {
        User b = newUser();
        User a = newUser();
        long p = postBy(b);

        collect(a, p);
        uncollect(a, p);
        collect(a, p);

        List<Map<String, Object>> rows = collectRows(b);
        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.get(0).get("delta")).intValue()).isEqualTo(2);
        assertThat(((Number) rows.get(1).get("delta")).intValue()).isEqualTo(-2);
        assertThat(rows.get(1).get("reason")).isEqualTo("UNCOLLECT");
        assertThat(((Number) rows.get(1).get("reverses_id")).longValue())
                .isEqualTo(((Number) rows.get(0).get("id")).longValue());
        assertThat(collectSum(b)).isZero();

        Place q = PlaceCollectIntegrationTest.newPlace(places, b);
        long ledgerBefore = jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ?", Long.class,
                b.getId());
        mvc.perform(post("/api/v1/places/" + q.getPublicToken() + "/collect")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(a.getId())))
                .andExpect(status().is2xxSuccessful());
        awaitAsyncIdle();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ?", Long.class,
                b.getId())).isEqualTo(ledgerBefore);
    }

    // ---------- AC7.5 ----------

    @Test
    @DisplayName("AC11c：下架后收藏人移除不可见条目 → 不扣；作者拉黑收藏人后对方移除 → 不扣")
    void removingInvisibleEntryDoesNotReverse() throws Exception {
        User b = newUser();
        User d = newUser();
        long p = postBy(b);
        collect(d, p);
        assertThat(collectSum(b)).isEqualTo(2);
        long lifetimeAfterCollect = lifetime(b);

        contentService.softDelete(p, DeleteReason.ADMIN_TAKEDOWN);
        awaitAsyncIdle();
        mvc.perform(get("/api/v1/me/collections/posts").header(HttpHeaders.AUTHORIZATION, userBearer(d.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].postId").value(p))
                .andExpect(jsonPath("$.items[0].available").value(false));
        uncollect(d, p);

        assertThat(collectRows(b)).hasSize(1);
        assertThat(collectSum(b)).isEqualTo(2);
        assertThat(lifetime(b)).isEqualTo(lifetimeAfterCollect);

        User a = newUser();
        long p2 = postBy(b);
        collect(a, p2);
        assertThat(collectSum(b)).isEqualTo(4);
        hideService.block(b.getId(), a.getId());
        uncollect(a, p2);
        assertThat(collectSum(b)).isEqualTo(4);
        assertThat(collectRows(b)).hasSize(2);
    }

    // ---------- AC7.6 ----------

    @Test
    @DisplayName("虚拟号作者的帖被收藏 → 收藏成功、作者无流水")
    void virtualAuthorGetsNothing() throws Exception {
        User virtual = users.saveAndFlush(User.newVirtual("virtual:pc-" + SEQ.incrementAndGet(), "v", null,
                adminActorId()));
        long p = postBy(virtual);
        User a = newUser();

        collect(a, p);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_collections WHERE user_id = ? AND post_id = ?",
                Long.class, a.getId(), p)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ?", Long.class,
                virtual.getId())).isZero();
    }
}
