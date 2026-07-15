package com.tailtopia.admin.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（Story 9.9）：评论主动下架/恢复状态 + 审计 + 幂等 + 404。纯 Mockito。 */
class AdminCommentModerationServiceTest {

    private CommentRepository comments;
    private AdminAuditService audit;
    private AdminCommentModerationService svc;

    @BeforeEach
    void setUp() {
        comments = Mockito.mock(CommentRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        svc = new AdminCommentModerationService(comments, audit);
    }

    private Comment comment(long id, boolean deleted) {
        Comment c = Comment.create(9L, null, 3L, "内容"); // postId, parentId, authorId, body
        set(c, "id", id);
        if (deleted) {
            c.softDelete();
        }
        when(comments.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void takedownSoftDeletesAndAudits() {
        Comment c = comment(5L, false);
        svc.takedown(5L, 7L);
        assertThat(c.isDeleted()).isTrue();
        verify(comments).save(c);
        verify(audit).record(eq(7L), eq("COMMENT_TAKEN_DOWN"), eq("comment"), eq("5"), anyString());
    }

    @Test
    void takedownIdempotentWhenAlreadyDeleted() {
        comment(5L, true);
        svc.takedown(5L, 7L);
        verify(comments, never()).save(any());
        verify(audit, never()).record(Mockito.anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void restoreClearsDeletedAndAudits() {
        Comment c = comment(5L, true);
        svc.restore(5L, 7L);
        assertThat(c.isDeleted()).isFalse();
        verify(comments).save(c);
        verify(audit).record(eq(7L), eq("COMMENT_RESTORED"), eq("comment"), eq("5"), anyString());
    }

    @Test
    void restoreIdempotentWhenNotDeleted() {
        comment(5L, false);
        svc.restore(5L, 7L);
        verify(comments, never()).save(any());
    }

    @Test
    void notFoundThrows() {
        when(comments.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.takedown(99L, 7L)).isInstanceOf(AppException.class);
    }

    private static void set(Object o, String field, Object value) {
        try {
            var f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(o, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
