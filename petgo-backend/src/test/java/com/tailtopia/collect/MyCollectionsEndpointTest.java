package com.tailtopia.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.collect.dto.CollectedPlaceRow;
import com.tailtopia.collect.dto.CollectedPostRow;
import com.tailtopia.collect.dto.CollectionPage;
import com.tailtopia.collect.service.MyCollectionQuery;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.event.ContentUncollectedEvent;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.domain.ReportReason;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.social.service.UserHideRelationService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * L1（需 Docker postgres + redis，云端不跑）：V1.4.0 ④ Story 2.2 —— 我的收藏列表端到端。
 */
@RecordApplicationEvents
class MyCollectionsEndpointTest extends ApiIntegrationTest {

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private PlaceRepository places;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MyCollectionQuery query;
    @Autowired
    private UserHideRelationService hides;
    @Autowired
    private ReportService reports;
    @Autowired
    private VetTestSupport vets;
    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private ApplicationEvents events;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("applicationTaskExecutor")
    private org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor asyncExecutor;

    /**
     * 等业务 {@code @Async} 池无在跑任务且队列为空（最多 10s；写法同 {@code FollowIntegrationTest}）。
     * 四线合并后 ① 的 {@code GrowthActivityListener} 异步监听收藏 / 发帖事件入账，其语句会计进
     * SessionFactory 级 {@code Statistics} —— 不排空就把别的线程的语句算到本页查询头上。
     */
    private void awaitAsyncIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        java.util.concurrent.ThreadPoolExecutor pool = asyncExecutor.getThreadPoolExecutor();
        while (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("业务异步池 10s 内未排空");
            }
            Thread.sleep(20);
        }
    }

    private long newPost(User author) {
        return posts.save(ContentPost.publish(author.getId(), ContentType.DAILY, null, "正文", List.of("https://cdn/x.jpg")))
                .getId();
    }

    private void collect(User u, long postId) throws Exception {
        mvc.perform(post("/api/v1/content-posts/" + postId + "/collect")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk());
    }

    private void collectPlace(User u, String token) throws Exception {
        mvc.perform(post("/api/v1/places/" + token + "/collect")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(u.getId())))
                .andExpect(status().isOk());
    }

    private List<CollectedPostRow> allPosts(User u) {
        List<CollectedPostRow> out = new ArrayList<>();
        String cursor = null;
        do {
            CollectionPage<CollectedPostRow> page = query.posts(u.getId(), cursor);
            out.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        return out;
    }

    private CollectedPostRow rowOf(User u, long postId) {
        return allPosts(u).stream().filter(r -> r.postId() == postId).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("25 条帖子收藏 → 20 / 5、倒序、无重复无遗漏；同刻 3 条跨页边界不丢不重")
    void pagination() throws Exception {
        User me = newUser();
        User author = newUser();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long id = newPost(author);
            ids.add(id);
            jdbc.update("INSERT INTO content_collections (user_id, post_id, created_at) VALUES (?, ?, "
                    + "now() - (? * interval '1 second'))", me.getId(), id, 100 - i);
        }
        // 第 19、20、21 条（按倒序）同一时刻
        jdbc.update("UPDATE content_collections SET created_at = '2026-01-01T00:00:00Z' WHERE user_id = ? AND post_id IN (?, ?, ?)",
                me.getId(), ids.get(4), ids.get(5), ids.get(6));

        CollectionPage<CollectedPostRow> first = query.posts(me.getId(), null);
        assertThat(first.items()).hasSize(20);
        assertThat(first.hasMore()).isTrue();
        CollectionPage<CollectedPostRow> second = query.posts(me.getId(), first.nextCursor());
        assertThat(second.items()).hasSize(5);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
        Set<Long> seen = new HashSet<>();
        first.items().forEach(r -> assertThat(seen.add(r.postId())).isTrue());
        second.items().forEach(r -> assertThat(seen.add(r.postId())).isTrue());
        assertThat(seen).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    @DisplayName("不可见：删除 / 下架 / 注销作者 / 预处置 / 我拉黑 / 被拉黑 / 举报帖 / 举报作者；恢复后可见；自己预处置帖可见")
    void invisibleReasonsAndRecovery() throws Exception {
        User me = newUser();
        List<User> authors = new ArrayList<>();
        List<Long> ps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            User a = newUser();
            authors.add(a);
            long p = newPost(a);
            ps.add(p);
            collect(me, p);
        }
        jdbc.update("UPDATE content_posts SET deleted_at = now() WHERE id IN (?, ?)", ps.get(0), ps.get(1));
        jdbc.update("UPDATE content_posts SET status = 'AUTHOR_DEACTIVATED' WHERE id = ?", ps.get(2));
        jdbc.update("UPDATE content_posts SET status = 'UNDER_REVIEW', report_hidden_at = now() WHERE id = ?", ps.get(3));
        hides.block(me.getId(), authors.get(4).getId());
        hides.block(authors.get(5).getId(), me.getId());
        reports.submit(ps.get(6), me.getId(), ReportReason.INAPPROPRIATE);
        hides.hideByReport(me.getId(), authors.get(7).getId());

        for (long p : ps) {
            CollectedPostRow r = rowOf(me, p);
            assertThat(r.available()).as("post %s", p).isFalse();
            assertThat(r.post()).isNull();
        }

        jdbc.update("UPDATE content_posts SET deleted_at = NULL WHERE id = ?", ps.get(0));
        jdbc.update("UPDATE content_posts SET status = 'PUBLISHED', report_hidden_at = NULL WHERE id = ?", ps.get(3));
        hides.unblock(me.getId(), authors.get(4).getId());
        assertThat(rowOf(me, ps.get(0)).available()).isTrue();
        assertThat(rowOf(me, ps.get(3)).available()).isTrue();
        assertThat(rowOf(me, ps.get(4)).available()).isTrue();

        long own = newPost(me);
        collect(me, own);
        jdbc.update("UPDATE content_posts SET status = 'UNDER_REVIEW', report_hidden_at = now() WHERE id = ?", own);
        assertThat(rowOf(me, own).available()).isTrue();
    }

    @Test
    @DisplayName("场所：下架不可见仍有名称、恢复可见；合并去重显示保留场所；移除后列表为空；帖子移除不发 Uncollected")
    void placesAndRemoval() throws Exception {
        User me = newUser();
        Place a = PlaceCollectIntegrationTest.newPlace(places, me);
        Place b = PlaceCollectIntegrationTest.newPlace(places, me);
        Place c = PlaceCollectIntegrationTest.newPlace(places, me);
        collectPlace(me, b.getPublicToken());
        collectPlace(me, a.getPublicToken());
        collectPlace(me, c.getPublicToken());
        jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = ? WHERE id = ?", a.getId(), b.getId());
        jdbc.update("UPDATE places SET status = 'DELISTED' WHERE id = ?", c.getId());

        List<CollectedPlaceRow> rows = query.places(me.getId(), null).items();
        assertThat(rows).hasSize(2);
        CollectedPlaceRow merged = rows.stream().filter(r -> r.placeToken().equals(a.getPublicToken())).findFirst()
                .orElseThrow();
        assertThat(merged.available()).isTrue();
        CollectedPlaceRow delisted = rows.stream().filter(r -> r.placeToken().equals(c.getPublicToken())).findFirst()
                .orElseThrow();
        assertThat(delisted.available()).isFalse();
        assertThat(delisted.place()).isNull();
        assertThat(delisted.name()).isEqualTo(c.getName());

        long p = newPost(newUser());
        collect(me, p);
        jdbc.update("UPDATE content_posts SET deleted_at = now() WHERE id = ?", p);
        for (CollectedPlaceRow r : rows) {
            mvc.perform(delete("/api/v1/places/" + r.placeToken() + "/collect")
                    .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()))).andExpect(status().isOk());
        }
        mvc.perform(delete("/api/v1/content-posts/" + p + "/collect")
                .header(HttpHeaders.AUTHORIZATION, userBearer(me.getId()))).andExpect(status().isOk());
        assertThat(query.places(me.getId(), null).items()).isEmpty();
        assertThat(query.posts(me.getId(), null).items()).isEmpty();
        assertThat(events.stream(ContentUncollectedEvent.class).filter(e -> e.postId() == p).count()).isZero();
    }

    @Test
    @DisplayName("只返回本人；兽医 403；游客 401")
    void security() throws Exception {
        User me = newUser();
        User other = newUser();
        collect(other, newPost(newUser()));
        assertThat(query.posts(me.getId(), null).items()).isEmpty();
        String vet = vetBearer(vets.newActiveVet("my-collections-it").getId());
        for (String url : List.of("/api/v1/me/collections/posts", "/api/v1/me/collections/places")) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, vet)).andExpect(status().isForbidden());
            mvc.perform(get(url + "?userId=" + other.getId()).header(HttpHeaders.AUTHORIZATION, userBearer(me.getId())))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("N+1 守卫：5 条与 20 条帖子收藏的首页语句数相等且 ≤ 5；场所 ≤ 3")
    void statementCountIndependentOfPageSize() throws Exception {
        User small = newUser();
        User big = newUser();
        for (int i = 0; i < 5; i++) {
            collect(small, newPost(newUser()));
        }
        for (int i = 0; i < 20; i++) {
            User a = newUser();
            collect(big, newPost(a));
            if (i == 3) {
                hides.block(big.getId(), a.getId());
            }
        }
        awaitAsyncIdle();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        query.posts(small.getId(), null);
        long s5 = stats.getPrepareStatementCount();
        stats.clear();
        query.posts(big.getId(), null);
        long s20 = stats.getPrepareStatementCount();
        for (int i = 0; i < 3; i++) {
            collectPlace(big, PlaceCollectIntegrationTest.newPlace(places, big).getPublicToken());
        }
        awaitAsyncIdle();
        stats.clear();
        query.places(big.getId(), null);
        long sp = stats.getPrepareStatementCount();
        stats.setStatisticsEnabled(false);

        assertThat(s5).isEqualTo(s20).isLessThanOrEqualTo(5);
        assertThat(sp).isLessThanOrEqualTo(3);
    }
}
