package com.tailtopia.admin.warmreply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.repository.WarmReplyFollowupRepository;
import com.tailtopia.admin.warmreply.service.WarmReplyEnqueueListener;
import com.tailtopia.admin.warmreply.service.WarmReplyQueueService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.event.CommentRemovedEvent;
import com.tailtopia.content.event.CommentRemovedReason;
import com.tailtopia.content.event.ContentCommentedEvent;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L0：暖贴跟进入队 / 出队判定（V1.3.0 Story 4.3 AC2 / AC3 / AC4）——仓储 / JDBC 全 mock，只验判定与 upsert 参数：
 * 虚拟一级评论被真实用户回复 → upsert；一级评论 / 回复者虚拟 / 被回复者真实 / 无 parentCommentId → 不入队（D-35：二级虚拟评论被回复识别不出）；
 * 出队只在 parentId 非空、回复者为真实用户且存在 PENDING 项时减一；一级被删则整条删 PENDING 项。
 */
class WarmReplyQueueServiceTest {

    private final WarmReplyFollowupRepository followups = mock(WarmReplyFollowupRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WarmReplyQueueService service = new WarmReplyQueueService(followups, users, jdbc);
    private final WarmReplyEnqueueListener listener = new WarmReplyEnqueueListener(service);

    private static final long POST = 10L;
    private static final long VIRTUAL_USER = 20L;
    private static final long REAL_USER = 30L;
    private static final long VIRTUAL_COMMENT = 100L;
    private static final long REPLY = 101L;

    private void stubUsers() {
        when(users.findById(VIRTUAL_USER)).thenReturn(Optional.of(User.newVirtual("virtual:x", "马甲", null, 1L)));
        when(users.findById(REAL_USER)).thenReturn(Optional.of(User.newGoogleUser("g", "g@t.test", "G", null)));
    }

    @Test
    void realReplyToVirtualTopLevelIsEnqueuedByUpsert() {
        stubUsers();
        Instant at = Instant.now();
        ContentCommentedEvent e = new ContentCommentedEvent(POST, REPLY, REAL_USER, 5L, VIRTUAL_USER, at, VIRTUAL_COMMENT);
        assertThat(service.enqueueIfWarmReply(e)).isTrue();
        verify(jdbc).update(eq(WarmReplyQueueService.UPSERT_SQL), eq(VIRTUAL_COMMENT), eq(POST), eq(VIRTUAL_USER), eq(REPLY), any());
        assertThat(WarmReplyQueueService.UPSERT_SQL).contains("ON CONFLICT (virtual_comment_id) WHERE status = 'PENDING'")
                .contains("pending_reply_count + 1");
    }

    @Test
    void notEnqueuedWhenTopLevelOrVirtualReplierOrRealParent() {
        stubUsers();
        // 一级评论：parentAuthorId / parentCommentId 为空
        assertThat(service.enqueueIfWarmReply(new ContentCommentedEvent(POST, REPLY, REAL_USER, 5L, null, Instant.now(), null))).isFalse();
        // 回复者是虚拟账号
        assertThat(service.enqueueIfWarmReply(new ContentCommentedEvent(POST, REPLY, VIRTUAL_USER, 5L, VIRTUAL_USER, Instant.now(), VIRTUAL_COMMENT))).isFalse();
        // 被回复者是真实用户
        assertThat(service.enqueueIfWarmReply(new ContentCommentedEvent(POST, REPLY, REAL_USER, 5L, REAL_USER, Instant.now(), VIRTUAL_COMMENT))).isFalse();
        // D-35：二级虚拟评论被回复 → 事件里 parentAuthorId 是一级作者（真实用户），识别不出，不入队
        assertThat(service.enqueueIfWarmReply(new ContentCommentedEvent(POST, REPLY, REAL_USER, 5L, REAL_USER, Instant.now(), 99L))).isFalse();
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void listenerSkipsTopLevelAndSwallowsFailures() {
        stubUsers();
        listener.onCommented(new ContentCommentedEvent(POST, REPLY, REAL_USER, 5L, null, Instant.now(), null));
        verify(users, never()).findById(anyLong());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("db"));
        listener.onCommented(new ContentCommentedEvent(POST, REPLY, REAL_USER, 5L, VIRTUAL_USER, Instant.now(), VIRTUAL_COMMENT)); // 不抛
    }

    @Test
    void dequeueOnlyWhenPendingExistsAndReplierIsReal() {
        stubUsers();
        when(followups.findByVirtualCommentIdAndStatus(VIRTUAL_COMMENT, FollowupStatus.PENDING)).thenReturn(Optional.empty());
        assertThat(service.onReplyRemoved(REPLY, VIRTUAL_COMMENT, REAL_USER)).isFalse();
        assertThat(service.onReplyRemoved(REPLY, null, REAL_USER)).isFalse();
        // 虚拟号回虚拟号的那条被删：当初没入队，这里也不查、不减（复审 #1）
        assertThat(service.onReplyRemoved(REPLY, VIRTUAL_COMMENT, VIRTUAL_USER)).isFalse();
        verify(followups, org.mockito.Mockito.times(1)).findByVirtualCommentIdAndStatus(anyLong(), any());
        verify(jdbc, never()).update(anyString(), anyLong());
    }

    @Test
    void topLevelRemovedDropsPendingItem() {
        // 一级评论被删（parentId 空）：监听器走「整条删除」，防孤儿（复审 #3）
        when(jdbc.update(anyString(), eq(VIRTUAL_COMMENT))).thenReturn(1);
        listener.onRemoved(new CommentRemovedEvent(VIRTUAL_COMMENT, POST, VIRTUAL_USER, null, CommentRemovedReason.ADMIN_TAKEDOWN, Instant.now()));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("DELETE FROM warm_reply_followups WHERE virtual_comment_id = ? AND status = 'PENDING'"),
                eq(VIRTUAL_COMMENT));
        verify(followups, never()).findByVirtualCommentIdAndStatus(anyLong(), any());
    }

    @Test
    void adminRoleAccountIsNotARealReplier() {
        // Story 3.1 唯一出口 isSyntheticAccount：role=ADMIN 的账号回暖评不入队（AD-8）
        User admin = User.newGoogleUser("admin:x", "a@t.test", "A", null);
        try {
            var f = User.class.getDeclaredField("role");
            f.setAccessible(true);
            f.set(admin, com.tailtopia.auth.domain.Role.ADMIN);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        assertThat(admin.isSyntheticAccount()).isTrue();
        when(users.findById(VIRTUAL_USER)).thenReturn(Optional.of(User.newVirtual("virtual:x", "马甲", null, 1L)));
        when(users.findById(40L)).thenReturn(Optional.of(admin));
        assertThat(service.enqueueIfWarmReply(new ContentCommentedEvent(POST, REPLY, 40L, 5L, VIRTUAL_USER, Instant.now(), VIRTUAL_COMMENT))).isFalse();
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void pendingCountDelegatesToRepository() {
        when(followups.countByStatus(FollowupStatus.PENDING)).thenReturn(4L);
        assertThat(service.pendingCount()).isEqualTo(4L);
    }
}
