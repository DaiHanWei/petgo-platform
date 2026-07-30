package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.event.CommentSubmittedEvent;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * L0：一级/二级写入 + 两级归并 + 删除权限矩阵 + 级联软删 + 互动事件（AC1/AC2）
 * + story 3 审核创建路由 / 队列门面（AC-B3/B4/B6/B8）。
 */
class CommentServiceTest {

    private CommentRepository comments;
    private ContentPostRepository posts;
    private AccountQueryService accounts;
    private ApplicationEventPublisher events;
    private ContentModerationService moderation;
    private ManualReviewGate reviewGate;
    private CommentService service;

    @BeforeEach
    void setUp() {
        comments = mock(CommentRepository.class);
        posts = mock(ContentPostRepository.class);
        accounts = mock(AccountQueryService.class);
        events = mock(ApplicationEventPublisher.class);
        moderation = mock(ContentModerationService.class);
        reviewGate = mock(ManualReviewGate.class);
        service = new CommentService(comments, posts, accounts, events, moderation, reviewGate);
        // 默认审核放行（PASS）——现网正常路径；各审核态在专项测试内覆写。
        when(moderation.moderateComment(anyString())).thenReturn(CommentVerdict.PASS);
        when(accounts.findAuthorViews(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return java.util.Map.of((Long) ids.get(0),
                    new AuthorView((Long) ids.get(0), "u", null, false));
        });
        // save 回填 id（仅对新建实体；已有 id 的更新/软删不改 id）。
        when(comments.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            if (c.getId() == null) {
                setField(c, Comment.class, "id", 500L);
                setField(c, Comment.class, "createdAt", Instant.now());
            }
            return c;
        });
    }

    private void postBy(long postId, long authorId) {
        ContentPost p = ContentPost.publish(authorId, ContentType.DAILY, null, "x", null);
        setField(p, ContentPost.class, "id", postId);
        setField(p, ContentPost.class, "createdAt", Instant.now());
        when(posts.findById(postId)).thenReturn(Optional.of(p));
    }

    private Comment existingComment(long id, Long parentId, long authorId, long postId) {
        Comment c = newComment();
        setField(c, Comment.class, "id", id);
        setField(c, Comment.class, "parentId", parentId);
        setField(c, Comment.class, "authorId", authorId);
        setField(c, Comment.class, "postId", postId);
        setField(c, Comment.class, "createdAt", Instant.now());
        return c;
    }

    private static Comment newComment() {
        try {
            var ctor = Comment.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object o, Class<?> cls, String field, Object value) {
        try {
            var f = cls.getDeclaredField(field);
            f.setAccessible(true);
            f.set(o, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void topLevelCommentSavedUnderReviewPublishesSubmittedEvent() {
        // 异步无感知：提交即落 UNDER_REVIEW（评论人自己可见）+ 发 CommentSubmittedEvent（触发异步审核），
        // 不发 ContentCommentedEvent（通过才通知楼主）、不在创建时入队。
        postBy(1L, 7L);
        service.createTopLevel(1L, 9L, "nice");
        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        verify(comments).save(cap.capture());
        assertThat(cap.getValue().getParentId()).isNull(); // 一级
        assertThat(cap.getValue().getModerationStatus()).isEqualTo(CommentModerationStatus.UNDER_REVIEW);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOf(CommentSubmittedEvent.class);
        assertThat(((CommentSubmittedEvent) ev.getValue()).commentId()).isEqualTo(500L);
        verify(reviewGate, never()).enqueueComment(anyLong(), anyInt());
    }

    @Test
    void replyToTopLevelAttachesToThatParent() {
        when(comments.findById(10L)).thenReturn(Optional.of(existingComment(10L, null, 3L, 1L)));
        postBy(1L, 7L);
        service.createReply(10L, 9L, "reply");
        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        verify(comments).save(cap.capture());
        assertThat(cap.getValue().getParentId()).isEqualTo(10L); // 挂到一级 10
    }

    @Test
    void replyToReplyMergesToTopLevelParentNoThirdLevel() {
        // 被回复者 20 本身是二级（parentId=10）→ 新回复应归并到一级 10。
        when(comments.findById(20L)).thenReturn(Optional.of(existingComment(20L, 10L, 4L, 1L)));
        when(comments.findById(10L)).thenReturn(Optional.of(existingComment(10L, null, 3L, 1L)));
        postBy(1L, 7L);
        service.createReply(20L, 9L, "reply");
        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        verify(comments).save(cap.capture());
        assertThat(cap.getValue().getParentId()).isEqualTo(10L); // 归并到一级，绝不三级
    }

    @Test
    void commentAuthorCanDeleteOwn() {
        when(comments.findById(30L)).thenReturn(Optional.of(existingComment(30L, 10L, 9L, 1L)));
        postBy(1L, 7L);
        service.delete(30L, 9L); // 评论作者本人
        verify(comments, times(1)).save(any(Comment.class));
    }

    @Test
    void contentAuthorCanDeleteAnyCommentOnTheirPost() {
        when(comments.findById(30L)).thenReturn(Optional.of(existingComment(30L, 10L, 9L, 1L)));
        postBy(1L, 7L);
        service.delete(30L, 7L); // 内容作者 7
        verify(comments, times(1)).save(any(Comment.class));
    }

    @Test
    void unrelatedUserCannotDelete() {
        when(comments.findById(30L)).thenReturn(Optional.of(existingComment(30L, 10L, 9L, 1L)));
        postBy(1L, 7L);
        assertThatThrownBy(() -> service.delete(30L, 999L)).isInstanceOf(AppException.class);
        verify(comments, never()).save(any());
    }

    @Test
    void deletingTopLevelCascadesToReplies() {
        Comment top = existingComment(40L, null, 9L, 1L); // 一级
        when(comments.findById(40L)).thenReturn(Optional.of(top));
        postBy(1L, 7L);
        when(comments.findByParentIdAndDeletedAtIsNull(40L))
                .thenReturn(List.of(existingComment(41L, 40L, 5L, 1L), existingComment(42L, 40L, 6L, 1L)));

        service.delete(40L, 9L);
        // 一级 + 2 条二级共 3 次 save（软删）。
        verify(comments, times(3)).save(any(Comment.class));
    }

    @Test
    void deletingReplyDoesNotCascade() {
        Comment reply = existingComment(50L, 40L, 9L, 1L); // 二级
        when(comments.findById(50L)).thenReturn(Optional.of(reply));
        postBy(1L, 7L);
        service.delete(50L, 9L);
        verify(comments, times(1)).save(any(Comment.class)); // 仅该条
        verify(comments, never()).findByParentIdAndDeletedAtIsNull(org.mockito.ArgumentMatchers.anyLong());
    }

    // ===== story 3 异步化：创建路由（L1 同步即拒 / 其余落挂起走异步） =====

    @Test
    void l1BlockedRejectsImmediatelyNeverPersists() {
        // L1 硬黑名单：同步即时 422（明显违规秒拒），从未落库/不发事件/不入队。
        postBy(1L, 7L);
        when(moderation.isL1Blocked(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.createTopLevel(1L, 9L, "judi"))
                .isInstanceOf(AppException.class);
        verify(comments, never()).save(any());
        verify(events, never()).publishEvent(any());
        verify(reviewGate, never()).enqueueComment(anyLong(), anyInt());
        // L1 预检不打三方（无网络）。
        verify(moderation, never()).moderateComment(anyString());
    }

    @Test
    void cleanReplySavedUnderReviewPublishesSubmittedEvent() {
        when(comments.findById(10L)).thenReturn(Optional.of(existingComment(10L, null, 3L, 1L)));
        postBy(1L, 7L);
        service.createReply(10L, 9L, "reply");
        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        verify(comments).save(cap.capture());
        assertThat(cap.getValue().getModerationStatus()).isEqualTo(CommentModerationStatus.UNDER_REVIEW);
        assertThat(cap.getValue().getParentId()).isEqualTo(10L);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOf(CommentSubmittedEvent.class);
        verify(reviewGate, never()).enqueueComment(anyLong(), anyInt()); // 创建时不入队
    }

    @Test
    void queueForReviewDelegatesToGate() {
        service.queueForReview(500L, 3);
        verify(reviewGate).enqueueComment(500L, 3);
    }

    // ===== story 3：队列处置门面（AC-B4/B6） =====

    @Test
    void approveCommentTurnsVisibleAndPublishesNewCommentEvent() {
        Comment c = underReview(70L, null, 9L, 1L);
        when(comments.findById(70L)).thenReturn(Optional.of(c));
        postBy(1L, 7L);
        service.approveComment(70L);
        assertThat(c.getModerationStatus()).isEqualTo(CommentModerationStatus.VISIBLE);
        verify(comments).save(c);
        ArgumentCaptor<ContentCommentedEvent> ev = ArgumentCaptor.forClass(ContentCommentedEvent.class);
        verify(events).publishEvent(ev.capture()); // G4：转可见此刻才发新评论通知
        assertThat(ev.getValue().contentAuthorId()).isEqualTo(7L);
    }

    @Test
    void rejectCommentTurnsRejectedNoEvent() {
        Comment c = underReview(71L, null, 9L, 1L);
        when(comments.findById(71L)).thenReturn(Optional.of(c));
        service.rejectComment(71L);
        assertThat(c.getModerationStatus()).isEqualTo(CommentModerationStatus.REJECTED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void takedownVisibleCommentReturnsSummaryThenIdempotent() {
        Comment c = existingComment(72L, null, 9L, 1L); // VISIBLE
        when(comments.findById(72L)).thenReturn(Optional.of(c));
        var first = service.takedownComment(72L);
        assertThat(first).isPresent();
        assertThat(c.getModerationStatus()).isEqualTo(CommentModerationStatus.TAKEN_DOWN);
        // 幂等：再次下架 → 空（不重复通知/审计）。
        assertThat(service.takedownComment(72L)).isEmpty();
    }

    @Test
    void restoreTakenDownCommentTurnsVisibleNoEvent() {
        Comment c = existingComment(73L, null, 9L, 1L);
        c.takedown();
        when(comments.findById(73L)).thenReturn(Optional.of(c));
        service.restoreComment(73L);
        assertThat(c.getModerationStatus()).isEqualTo(CommentModerationStatus.VISIBLE);
        verify(events, never()).publishEvent(any());
    }

    private Comment underReview(long id, Long parentId, long authorId, long postId) {
        Comment c = existingComment(id, parentId, authorId, postId);
        setField(c, Comment.class, "moderationStatus", CommentModerationStatus.UNDER_REVIEW);
        return c;
    }
}
