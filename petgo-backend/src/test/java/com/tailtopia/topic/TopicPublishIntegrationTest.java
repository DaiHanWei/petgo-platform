package com.tailtopia.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.topic.domain.TopicNameRules;
import com.tailtopia.topic.service.AttachedTopic;
import com.tailtopia.topic.service.TopicService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * V1.4.2 social-topic · Story 4.1 · AC4 / AC5 / AC6.3 · L1（需 postgres + redis；云端不跑，待本地验收）。
 *
 * <p>走真实 {@code POST /api/v1/content-posts}：新建用户话题 / 再发同名 / 私密 / DISABLED / HIDDEN / 6 个话题 /
 * Lark 定时发帖（{@code publishTrusted}）/ 并发两事务同名新话题。话题名每次带随机后缀，避免共享库互相干扰。
 */
class TopicPublishIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ContentService contentService;
    @Autowired
    private TopicService topicService;
    @Autowired
    private PlatformTransactionManager txManager;

    /** 随机后缀（字母，合法话题字符；保证总长 ≤ 30）。 */
    private static String uniq(String base) {
        String s = UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        return base + s.substring(0, Math.min(10, s.length()));
    }

    private JsonNode publish(long userId, String text) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("type", "DAILY", "text", text));
        String resp = mvc.perform(post("/api/v1/content-posts")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp);
    }

    private int topicRows(String norm) {
        return jdbc.queryForObject("SELECT count(*) FROM topics WHERE name_norm = ?", Integer.class, norm);
    }

    private int links(long postId) {
        return jdbc.queryForObject("SELECT count(*) FROM content_topics WHERE post_id = ?", Integer.class, postId);
    }

    private long insertTopic(String display, String status) {
        return jdbc.queryForObject("INSERT INTO topics (name_display, name_norm, type, status) "
                + "VALUES (?, ?, 'USER', ?) RETURNING id", Long.class, display, TopicNameRules.normalize(display), status);
    }

    @Test
    void newTopicCreatedThenReusedWithDisplayUnchanged() throws Exception {
        long u = newUser().getId();
        String display = "Kucing" + uniq("Lucu");
        String norm = TopicNameRules.normalize(display);

        JsonNode first = publish(u, "#" + display + " hari ini");
        assertThat(topicRows(norm)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name_display FROM topics WHERE name_norm = ?", String.class, norm))
                .isEqualTo(display);
        assertThat(jdbc.queryForObject("SELECT type || '/' || status FROM topics WHERE name_norm = ?",
                String.class, norm)).isEqualTo("USER/ACTIVE");
        assertThat(links(first.get("id").asLong())).isEqualTo(1);
        assertThat(first.get("topics").get(0).get("name").asText()).isEqualTo(norm);
        assertThat(first.get("topics").get(0).get("official").asBoolean()).isFalse();
        assertThat(first.get("topics").get(0).get("created").asBoolean()).isTrue();
        assertThat(first.get("topics").get(0).has("id")).isFalse();

        JsonNode second = publish(u, "lagi #" + norm);
        assertThat(topicRows(norm)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name_display FROM topics WHERE name_norm = ?", String.class, norm))
                .isEqualTo(display);
        assertThat(second.get("topics").get(0).get("created").asBoolean()).isFalse();
    }

    @Test
    void privatePostWritesNothing() {
        long u = newUser().getId();
        String name = uniq("rahasia");
        ContentPostResponse resp = contentService.publish(u, new ContentPostCreateRequest(ContentType.DAILY, null,
                "#" + name, null, null, ContentVisibility.PRIVATE), UUID.randomUUID().toString());
        assertThat(topicRows(TopicNameRules.normalize(name))).isZero();
        assertThat(links(resp.id())).isZero();
        assertThat(resp.topics()).isNull();
    }

    @Test
    void disabledSkippedHiddenLinked() throws Exception {
        long u = newUser().getId();
        String disabled = uniq("mati");
        String hidden = uniq("sembunyi");
        insertTopic(disabled, "DISABLED");
        long hiddenId = insertTopic(hidden, "HIDDEN");

        JsonNode resp = publish(u, "#" + disabled + " #" + hidden);
        long postId = resp.get("id").asLong();
        assertThat(links(postId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT topic_id FROM content_topics WHERE post_id = ?", Long.class, postId))
                .isEqualTo(hiddenId);
        assertThat(resp.get("topics")).hasSize(1);
        assertThat(resp.get("topics").get(0).get("name").asText()).isEqualTo(TopicNameRules.normalize(hidden));
    }

    @Test
    void onlyFirstFiveDistinctTopicsAreLinked() throws Exception {
        long u = newUser().getId();
        String p = uniq("t");
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            text.append("#").append(p).append("x".repeat(i)).append(' ');
        }
        JsonNode resp = publish(u, text.toString());
        assertThat(links(resp.get("id").asLong())).isEqualTo(5);
        assertThat(topicRows(TopicNameRules.normalize(p + "xxxxxx"))).isZero();
    }

    @Test
    void trustedPublishAlsoLinks() {
        long u = newUser().getId();
        String name = uniq("lark");
        ContentPostResponse resp = contentService.publishTrusted(u,
                new ContentPostCreateRequest(ContentType.DAILY, null, "#" + name + " jadwal", null),
                UUID.randomUUID().toString());
        assertThat(links(resp.id())).isEqualTo(1);
        assertThat(resp.topics()).extracting(t -> t.name()).containsExactly(TopicNameRules.normalize(name));
    }

    private long newPost(long u, String text) {
        return contentService.publish(u, new ContentPostCreateRequest(ContentType.DAILY, null, text, null),
                UUID.randomUUID().toString()).id();
    }

    /** 当前有多少条 {@code INSERT INTO topics} 正在等锁（证明 B 真的撞在 A 未提交的唯一索引行上）。 */
    private int waitingTopicInserts() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                + "WHERE wait_event_type = 'Lock' AND query LIKE '%INSERT INTO topics%'", Integer.class);
    }

    @Test
    void concurrentSameNewTopicYieldsOneRowTwoLinks() throws Exception {
        long u = newUser().getId();
        long p1 = newPost(u, "satu");
        long p2 = newPost(u, "dua");
        String name = uniq("balapan");
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch aInserted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A：事务内插入后停住（不提交），保证两个事务真的重叠。
            Future<List<AttachedTopic>> a = pool.submit(() -> tx.execute(s -> {
                List<AttachedTopic> r = topicService.attach(p1, "#" + name, ContentVisibility.PUBLIC);
                aInserted.countDown();
                try {
                    releaseA.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return r;
            }));
            assertThat(aInserted.await(30, TimeUnit.SECONDS)).isTrue();
            // B：A 未提交时起跑 —— 先查不到，INSERT 在唯一索引上等 A。
            Future<List<AttachedTopic>> b = pool.submit(() ->
                    tx.execute(s -> topicService.attach(p2, "#" + name, ContentVisibility.PUBLIC)));
            long deadline = System.currentTimeMillis() + 10_000;
            while (waitingTopicInserts() == 0) {
                assertThat(System.currentTimeMillis()).as("B 应在唯一索引上等待 A").isLessThan(deadline);
                Thread.sleep(50);
            }
            assertThat(b.isDone()).isFalse();
            releaseA.countDown();

            List<AttachedTopic> ra = a.get(30, TimeUnit.SECONDS);
            List<AttachedTopic> rb = b.get(30, TimeUnit.SECONDS);
            assertThat(ra).singleElement().satisfies(t -> assertThat(t.created()).isTrue());
            // B 走的是冲突路径：ON CONFLICT DO NOTHING 影响 0 行 → created=false，再查读到 A 的那行。
            assertThat(rb).singleElement().satisfies(t -> assertThat(t.created()).isFalse());
            assertThat(ra.get(0).topicId()).isEqualTo(rb.get(0).topicId());
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
        assertThat(topicRows(TopicNameRules.normalize(name))).isEqualTo(1);
        assertThat(links(p1) + links(p2)).isEqualTo(2);
    }

    /**
     * 反序并发（复审 CONFIRMED）：甲「#x #y」、乙「#y #x」同时新建，按出现顺序加锁会死锁、一帖 5xx。
     * 按标准名排序后加锁顺序一致 → 多轮都不抛异常、每个话题只一行、两帖各 2 条关联。
     */
    @Test
    void reverseOrderConcurrentNewTopicsDoNotDeadlock() throws Exception {
        long u = newUser().getId();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                long p1 = newPost(u, "satu" + round);
                long p2 = newPost(u, "dua" + round);
                String x = uniq("xa");
                String y = uniq("yb");
                CountDownLatch start = new CountDownLatch(1);
                Future<List<AttachedTopic>> a = pool.submit(() -> {
                    start.await();
                    return tx.execute(s -> topicService.attach(p1, "#" + x + " #" + y, ContentVisibility.PUBLIC));
                });
                Future<List<AttachedTopic>> b = pool.submit(() -> {
                    start.await();
                    return tx.execute(s -> topicService.attach(p2, "#" + y + " #" + x, ContentVisibility.PUBLIC));
                });
                start.countDown();
                // 返回值仍按各自正文出现顺序。
                assertThat(a.get(30, TimeUnit.SECONDS)).extracting(AttachedTopic::name)
                        .containsExactly(TopicNameRules.normalize(x), TopicNameRules.normalize(y));
                assertThat(b.get(30, TimeUnit.SECONDS)).extracting(AttachedTopic::name)
                        .containsExactly(TopicNameRules.normalize(y), TopicNameRules.normalize(x));
                assertThat(topicRows(TopicNameRules.normalize(x))).isEqualTo(1);
                assertThat(topicRows(TopicNameRules.normalize(y))).isEqualTo(1);
                assertThat(links(p1)).isEqualTo(2);
                assertThat(links(p2)).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
