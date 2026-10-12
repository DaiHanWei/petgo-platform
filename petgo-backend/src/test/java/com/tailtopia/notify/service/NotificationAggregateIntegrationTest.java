package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * L1：互动通知「未读即合并」与推送去抖（V1.3.4 follow · Story 3.1 · AC3 / AC5 / AC6.3）—— 需 Docker postgres+redis。
 *
 * <p>每个用例用唯一的 postId（{@code SEQ}）：去抖键 {@code notify:likepush:{postId}} 天然隔离，<b>不 FLUSH Redis</b>。
 * 推送用 {@link MockitoBean} 计数（真实推送是 {@code @Async}，桩掉后同步可验）。
 */
class NotificationAggregateIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private NotificationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoBean
    private NotificationPusher pusher;

    private long newPostId() {
        return SEQ.incrementAndGet();
    }

    private Notification like(User author, User liker, long postId) {
        return service.sendInteraction(author.getId(), NotificationType.CONTENT_LIKED, liker.getId(),
                "LIKE:POST:" + postId, String.valueOf(postId), null, null, null);
    }

    private long unreadRows(User recipient, long postId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ? "
                + "AND aggregate_key = ? AND is_read = false", Long.class, recipient.getId(), "LIKE:POST:" + postId);
    }

    private List<Long> actorsOf(long notificationId) {
        return jdbc.queryForList("SELECT actor_user_id FROM notification_actors WHERE notification_id = ? "
                + "ORDER BY acted_at DESC, actor_user_id DESC", Long.class, notificationId);
    }

    private long badge(User u) {
        String v = redis.opsForValue().get(NotificationService.UNREAD_KEY_PREFIX + u.getId());
        return v == null ? 0 : Long.parseLong(v);
    }

    @Test
    void threeLikesOnSamePostWhileUnreadMergeIntoOneRowAndBumpBadgeOnce() {
        User author = newUser();
        long postId = newPostId();
        long before = badge(author);

        Notification first = like(author, newUser(), postId);
        Notification second = like(author, newUser(), postId);
        Notification third = like(author, newUser(), postId);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(third.getId()).isEqualTo(first.getId());
        assertThat(unreadRows(author, postId)).isEqualTo(1);
        assertThat(actorsOf(first.getId())).hasSize(3);
        assertThat(badge(author) - before).as("合并不加角标").isEqualTo(1);
        assertThat(third.getDeepLinkToken()).as("合并沿用原行 token").isEqualTo(first.getDeepLinkToken());
        // 去抖：10 分钟内同帖只推一次。
        verify(pusher, times(1)).pushToUser(eq(author.getId()), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    /** T-29：同一人取消赞再赞、仍未读 → 什么都不做（actor 不重复、时间不刷新）。 */
    @Test
    void sameActorAgainDoesNotDuplicateOrRefresh() {
        User author = newUser();
        User liker = newUser();
        long postId = newPostId();
        Notification n = like(author, liker, postId);
        java.time.Instant createdAt = jdbc.queryForObject("SELECT created_at FROM notifications WHERE id = ?",
                java.sql.Timestamp.class, n.getId()).toInstant();

        like(author, liker, postId);

        assertThat(actorsOf(n.getId())).containsExactly(liker.getId());
        assertThat(jdbc.queryForObject("SELECT created_at FROM notifications WHERE id = ?",
                java.sql.Timestamp.class, n.getId()).toInstant()).isEqualTo(createdAt);
    }

    @Test
    void likeAfterMarkedReadStartsNewRow() {
        User author = newUser();
        long postId = newPostId();
        Notification first = like(author, newUser(), postId);
        jdbc.update("UPDATE notifications SET is_read = true, read_at = now() WHERE id = ?", first.getId());

        Notification next = like(author, newUser(), postId);

        assertThat(next.getId()).isNotEqualTo(first.getId());
        assertThat(unreadRows(author, postId)).isEqualTo(1);
    }

    /** 并发：两个线程同时给同帖点赞 → 仍只有 1 条未读、2 个 actor（部分唯一索引 + upsert 兜底）。 */
    @Test
    void concurrentLikesStillYieldOneUnreadRow() throws Exception {
        User author = newUser();
        User a = newUser();
        User b = newUser();
        long postId = newPostId();
        long before = badge(author);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Notification>> tasks = List.of(
                    () -> {
                        start.await();
                        return like(author, a, postId);
                    },
                    () -> {
                        start.await();
                        return like(author, b, postId);
                    });
            List<Future<Notification>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            long id1 = futures.get(0).get().getId();
            long id2 = futures.get(1).get().getId();

            assertThat(id1).isEqualTo(id2);
            assertThat(unreadRows(author, postId)).isEqualTo(1);
            assertThat(actorsOf(id1)).containsExactlyInAnyOrder(a.getId(), b.getId());
            // 3.1 code-review F9：并发下角标也只 +1、去抖后只推 1 次。
            assertThat(badge(author) - before).isEqualTo(1);
            verify(pusher, times(1)).pushToUser(eq(author.getId()), anyString(), anyString(), anyString(),
                    anyString(), anyString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void commentAndReplyEachGetOwnRowWithCommentIdAndVariant() {
        User author = newUser();
        User parent = newUser();
        User commenter = newUser();
        long postId = newPostId();

        Notification toAuthor = service.sendInteraction(author.getId(), NotificationType.CONTENT_COMMENTED,
                commenter.getId(), null, String.valueOf(postId), 700L, null, "halo");
        Notification toParent = service.sendInteraction(parent.getId(), NotificationType.CONTENT_COMMENTED,
                commenter.getId(), null, String.valueOf(postId), 700L, Notification.VARIANT_REPLY, "halo");
        Notification again = service.sendInteraction(author.getId(), NotificationType.CONTENT_COMMENTED,
                commenter.getId(), null, String.valueOf(postId), 701L, null, "lagi");

        assertThat(again.getId()).as("评论不聚合").isNotEqualTo(toAuthor.getId());
        assertThat(toAuthor.getCommentId()).isEqualTo(700L);
        assertThat(toAuthor.getVariant()).isNull();
        assertThat(toParent.getVariant()).isEqualTo(Notification.VARIANT_REPLY);
        assertThat(toAuthor.getTitle()).as("落库不含原文（AD-3）").doesNotContain("halo");
        assertThat(actorsOf(toParent.getId())).containsExactly(commenter.getId());
        // 评论每次都推，正文 = 原文。
        verify(pusher).pushToUser(eq(parent.getId()), anyString(), eq("halo"), eq("CONTENT_COMMENTED"),
                anyString(), eq(String.valueOf(postId)));
    }

    @Test
    void secondLikeWithinDebounceWindowIsNotPushedButAnotherPostIs() {
        User author = newUser();
        long postId = newPostId();
        like(author, newUser(), postId);
        clearInvocations(pusher);

        like(author, newUser(), postId);
        verify(pusher, never()).pushToUser(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString());

        like(author, newUser(), newPostId());
        verify(pusher, times(1)).pushToUser(eq(author.getId()), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }
}
