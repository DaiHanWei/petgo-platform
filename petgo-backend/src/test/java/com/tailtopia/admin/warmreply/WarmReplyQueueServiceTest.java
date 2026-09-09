package com.tailtopia.admin.warmreply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.domain.HandledAction;
import com.tailtopia.admin.warmreply.domain.WarmReplyFollowup;
import com.tailtopia.admin.warmreply.repository.WarmReplyFollowupRepository;
import com.tailtopia.admin.warmreply.service.WarmReplyEnqueueListener;
import com.tailtopia.admin.warmreply.service.WarmReplyQueueService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.event.CommentRemovedEvent;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
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
    private final CommentRepository comments = mock(CommentRepository.class);
    private final ContentPostRepository posts = mock(ContentPostRepository.class);
    private final CommentService commentService = mock(CommentService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final WarmReplyQueueService service = new WarmReplyQueueService(followups, users, jdbc, comments, posts, commentService,
            idempotency, audit);
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

    // ===== Story 4.4：回复 / 标记已读 =====

    /** 跟进行只由 upsert SQL 创建（无公开构造器）：测试用反射造一条 PENDING 项。 */
    static WarmReplyFollowup pendingFollowup(long id) {
        try {
            var ctor = WarmReplyFollowup.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            WarmReplyFollowup f = ctor.newInstance();
            for (var e : java.util.Map.of("id", (Object) id, "virtualCommentId", VIRTUAL_COMMENT, "postId", POST,
                    "virtualUserId", VIRTUAL_USER, "lastReplyId", REPLY, "lastReplyAt", Instant.now()).entrySet()) {
                var fld = WarmReplyFollowup.class.getDeclaredField(e.getKey());
                fld.setAccessible(true);
                fld.set(f, e.getValue());
            }
            return f;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void stubContentAlive() {
        when(posts.findById(POST)).thenReturn(Optional.of(ContentPost.publish(5L, ContentType.DAILY, null, "帖", java.util.List.of())));
        when(comments.findById(VIRTUAL_COMMENT)).thenReturn(Optional.of(Comment.create(POST, null, VIRTUAL_USER, "暖评")));
    }

    @Test
    void replyGoesThroughCreateReplyWithLockedIdentityThenHandledAndAudited() {
        WarmReplyFollowup f = pendingFollowup(7L);
        when(followups.findForUpdateById(7L)).thenReturn(Optional.of(f));
        when(idempotency.findResourceId("wr:7:k1")).thenReturn(Optional.empty());
        stubContentAlive();
        String longBody = "谢谢你！".repeat(20); // 80 字
        when(commentService.createReply(VIRTUAL_COMMENT, VIRTUAL_USER, longBody)).thenReturn(
                new CommentResponse(900L, VIRTUAL_USER, "马甲", null, false, null, longBody, Instant.now(), 0, java.util.List.of(), "UNDER_REVIEW"));

        WarmReplyQueueService.ReplyResult r = service.reply(7L, "  " + longBody + "  ", "k1", 42L);

        assertThat(r.commentId()).isEqualTo(900L);
        assertThat(r.replayed()).isFalse();
        // 身份锁定：parentId = 虚拟一级评论、authorId = 虚拟账号——都从跟进行取，不接收参数
        verify(commentService).createReply(VIRTUAL_COMMENT, VIRTUAL_USER, longBody);
        verify(comments, never()).save(any());
        assertThat(f.getStatus()).isEqualTo(FollowupStatus.HANDLED);
        assertThat(f.getHandledAction()).isEqualTo(HandledAction.REPLIED);
        assertThat(f.getHandledCommentId()).isEqualTo(900L);
        assertThat(f.getHandledBy()).isEqualTo(42L);
        org.mockito.ArgumentCaptor<String> summary = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(42L), eq(AuditActions.COMMENT_VIRTUAL_POST), eq("COMMENT"), eq("900"), summary.capture());
        assertThat(summary.getValue()).contains("followupId=7").doesNotContain(longBody).contains(longBody.substring(0, 50) + "…");
        verify(idempotency).store("wr:7:k1", 900L); // 幂等键按项加作用域
    }

    @Test
    void replyValidationOrderAndReplay() {
        when(idempotency.findResourceId("wr:7:dup")).thenReturn(Optional.of(900L));
        assertThat(service.reply(7L, "x", "dup", 42L)).isEqualTo(new WarmReplyQueueService.ReplyResult(900L, true));
        verify(commentService, never()).createReply(anyLong(), anyLong(), anyString());

        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        when(followups.findForUpdateById(8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reply(8L, "x", null, 42L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.warmReply.notFound"));

        WarmReplyFollowup handled = pendingFollowup(9L);
        handled.markHandled(1L, HandledAction.READ, null);
        when(followups.findForUpdateById(9L)).thenReturn(Optional.of(handled));
        assertThatThrownBy(() -> service.reply(9L, "x", null, 42L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.warmReply.notPending"));

        WarmReplyFollowup f = pendingFollowup(7L);
        when(followups.findForUpdateById(7L)).thenReturn(Optional.of(f));
        assertThatThrownBy(() -> service.reply(7L, "   ", null, 42L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.warmReply.bodyInvalid"));
        assertThatThrownBy(() -> service.reply(7L, "x".repeat(201), null, 42L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.warmReply.bodyInvalid"));

        // 帖子已删 → 只能标记已读
        when(posts.findById(POST)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reply(7L, "x", null, 42L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.warmReply.contentDeleted"));
        assertThat(f.getStatus()).isEqualTo(FollowupStatus.PENDING);
        verify(commentService, never()).createReply(anyLong(), anyLong(), anyString());
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void replyBlockedByL1KeepsPendingAndCarriesLocalizedCode() {
        WarmReplyFollowup f = pendingFollowup(7L);
        when(followups.findForUpdateById(7L)).thenReturn(Optional.of(f));
        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        stubContentAlive();
        when(commentService.createReply(anyLong(), anyLong(), anyString())).thenThrow(AppException.commentBlocked("内容包含不当词汇"));
        assertThatThrownBy(() -> service.reply(7L, "judi", "k", 42L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.v130.comments.virtual.err.blocked"));
        assertThat(f.getStatus()).isEqualTo(FollowupStatus.PENDING);
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void markReadAuditsAndRejectsSecondTime() {
        WarmReplyFollowup f = pendingFollowup(7L);
        when(followups.findForUpdateById(7L)).thenReturn(Optional.of(f));
        service.markRead(7L, 42L);
        assertThat(f.getStatus()).isEqualTo(FollowupStatus.HANDLED);
        assertThat(f.getHandledAction()).isEqualTo(HandledAction.READ);
        verify(audit).record(eq(42L), eq(AuditActions.WARM_REPLY_READ), eq("WARM_REPLY_FOLLOWUP"), eq("7"), anyString());
        assertThatThrownBy(() -> service.markRead(7L, 42L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.warmReply.notPending"));
    }
}
