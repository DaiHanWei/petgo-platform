package com.petgo.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.auth.dto.AuthorView;
import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.domain.Comment;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.event.ContentCommentedEvent;
import com.petgo.content.repository.CommentRepository;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** L0：一级/二级写入 + 两级归并 + 删除权限矩阵 + 级联软删 + 互动事件（AC1/AC2）。 */
class CommentServiceTest {

    private CommentRepository comments;
    private ContentPostRepository posts;
    private AccountQueryService accounts;
    private ApplicationEventPublisher events;
    private CommentService service;

    @BeforeEach
    void setUp() {
        comments = mock(CommentRepository.class);
        posts = mock(ContentPostRepository.class);
        accounts = mock(AccountQueryService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new CommentService(comments, posts, accounts, events);
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
    void topLevelCommentSavedAndEventPublished() {
        postBy(1L, 7L);
        service.createTopLevel(1L, 9L, "nice");
        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        verify(comments).save(cap.capture());
        assertThat(cap.getValue().getParentId()).isNull(); // 一级
        ArgumentCaptor<ContentCommentedEvent> ev = ArgumentCaptor.forClass(ContentCommentedEvent.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue().commenterId()).isEqualTo(9L);
        assertThat(ev.getValue().contentAuthorId()).isEqualTo(7L);
        assertThat(ev.getValue().parentAuthorId()).isNull();
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
}
