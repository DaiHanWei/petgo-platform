package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.profile.repository.UserNewbieMarkDao;
import com.tailtopia.profile.service.NewbieMarkService;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.social.event.PetFollowedEvent;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Connection;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1：V1.5.0 onboarding-recall Story 2.1 —— 真事件 → 异步监听 → {@code user_newbie_marks} 行；
 * 并发幂等、不撤、注销删行、迁移重放。
 */
class NewbieTaskIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private NewbieMarkService markService;
    @Autowired
    private UserNewbieMarkDao dao;
    @Autowired
    @Qualifier("applicationTaskExecutor")
    private ThreadPoolTaskExecutor asyncExecutor;

    private void publish(Object event) {
        new TransactionTemplate(txManager).executeWithoutResult(st -> events.publishEvent(event));
    }

    private long marks(long userId, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM user_newbie_marks WHERE user_id = ? AND task_key = ?",
                Long.class, userId, key);
    }

    @Test
    void likeMarksBothSidesAndSelfLikeMarksNothing() throws Exception {
        User a = newUser();
        User b = newUser();
        publish(new ContentLikedEvent(1L, a.getId(), b.getId(), Instant.now()));
        publish(new ContentLikedEvent(2L, b.getId(), b.getId(), Instant.now()));
        awaitAsyncIdle();
        assertThat(marks(a.getId(), "FIRST_LIKE_GIVEN")).isEqualTo(1);
        assertThat(marks(b.getId(), "FIRST_LIKE_RECEIVED")).isEqualTo(1);
        assertThat(marks(b.getId(), "FIRST_LIKE_GIVEN")).isZero();
    }

    @Test
    void replyMarksReplyNotComment() throws Exception {
        User a = newUser();
        User b = newUser();
        publish(new ContentCommentedEvent(1L, 2L, a.getId(), a.getId(), b.getId(), Instant.now(), 1L));
        awaitAsyncIdle();
        assertThat(marks(a.getId(), "FIRST_REPLY")).isEqualTo(1);
        assertThat(marks(a.getId(), "FIRST_COMMENT")).isZero();
    }

    @Test
    void petFollowMarksFollowButNotS19() throws Exception {
        User a = newUser();
        publish(new PetFollowedEvent(a.getId(), 999_999L, newUser().getId(), Instant.now()));
        awaitAsyncIdle();
        assertThat(marks(a.getId(), "FIRST_FOLLOW")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_milestone_flags WHERE user_id = ?",
                Long.class, a.getId())).isZero();
    }

    @Test
    void concurrentMarksWriteOneRowAndReportOnce() throws Exception {
        User a = newUser();
        AtomicInteger fresh = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                go.await();
                if (markService.markIfAbsent(a.getId(), NewbieTask.FIRST_LIKE_GIVEN)) {
                    fresh.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(fresh.get()).isEqualTo(1);
        assertThat(marks(a.getId(), "FIRST_LIKE_GIVEN")).isEqualTo(1);
    }

    @Test
    void deleteByUserIdRemovesRowsAndMigrationReplays() throws Exception {
        User a = newUser();
        markService.markIfAbsent(a.getId(), NewbieTask.FIRST_FOLLOW);
        assertThat(dao.keysOf(a.getId())).contains("FIRST_FOLLOW");
        dao.deleteByUserId(a.getId());
        assertThat(dao.keysOf(a.getId())).isEmpty();

        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c,
                    new ClassPathResource("db/migration/V20261115_0300__create_user_newbie_marks.sql"));
        }
    }

    @Test
    void replyGoPostIdPointsToMyPostCommentedByOthers() throws Exception {
        User me = newUser();
        long post = jdbc.queryForObject("INSERT INTO content_posts (author_id, type, status, visibility, created_at,"
                + " updated_at, content_version) VALUES (?, 'DAILY', 'PUBLISHED', 'PUBLIC', now(), now(), 1) RETURNING id",
                Long.class, me.getId());
        jdbc.update("INSERT INTO comments (post_id, author_id, body, created_at, updated_at, content_version)"
                + " VALUES (?, ?, 'hi', now(), now(), 1)", post, newUser().getId());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me/newbie-tasks")
                        .header("Authorization", userBearer(me.getId())).header("X-App-Version", "1.5.0"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.replyGoPostId").value((int) post));
    }

    private void awaitAsyncIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ThreadPoolExecutor pool = asyncExecutor.getThreadPoolExecutor();
        Thread.sleep(50);
        while (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("业务异步池 10s 内未排空");
            }
            Thread.sleep(20);
        }
    }
}
