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
import com.tailtopia.admin.warmreply.repository.PostDistributionQuery;
import com.tailtopia.admin.warmreply.service.VirtualCommentService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：暖评发布（V1.3.0 Story 4.2 AC3 / AC5 / AC6）：幂等短路不产生第二条；身份必须是启用中的虚拟账号；正文 1～200 字；
 * 帖子已删 404；成功路径只调 {@code CommentService.createTopLevel}（显式虚拟账号 id 作 authorId）+ 审计（正文只记前 50 字）+ 幂等记录。
 */
class VirtualCommentServiceTest {

    private final CommentService commentService = mock(CommentService.class);
    private final CommentRepository comments = mock(CommentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ContentPostRepository posts = mock(ContentPostRepository.class);
    private final ContentSpeciesResolver species = mock(ContentSpeciesResolver.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final SignedUrlService signed = mock(SignedUrlService.class);
    private final PostDistributionQuery distribution = mock(PostDistributionQuery.class);
    private final VirtualCommentService service = new VirtualCommentService(commentService, comments, users, posts, species,
            idempotency, audit, signed, distribution);

    private static User virtual(boolean enabled) {
        User v = User.newVirtual("virtual:x", "MeowSisters", null, 1L);
        if (!enabled) {
            v.setEnabled(false);
        }
        return v;
    }

    private static ContentPost publishedPost() {
        return ContentPost.publish(7L, ContentType.DAILY, null, "一条冷帖", List.of());
    }

    @Test
    void idempotentReplayShortCircuits() {
        when(idempotency.findResourceId("k1")).thenReturn(Optional.of(99L));
        VirtualCommentService.Result r = service.postAsVirtual(1L, 2L, "hi", "k1", 5L);
        assertThat(r.commentId()).isEqualTo(99L);
        assertThat(r.replayed()).isTrue();
        verify(commentService, never()).createTopLevel(anyLong(), anyLong(), anyString());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void identityMustBeEnabledVirtualAccount() {
        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        when(users.findById(2L)).thenReturn(Optional.of(virtual(false)));
        assertThatThrownBy(() -> service.postAsVirtual(1L, 2L, "hi", "k", 5L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.virtualComment.identityInvalid"));
        when(users.findById(3L)).thenReturn(Optional.of(User.newGoogleUser("g", "g@t.test", "G", null)));
        assertThatThrownBy(() -> service.postAsVirtual(1L, 3L, "hi", "k", 5L)).isInstanceOf(AppException.class);
        when(users.findById(4L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.postAsVirtual(1L, 4L, "hi", "k", 5L)).isInstanceOf(AppException.class);
        verify(commentService, never()).createTopLevel(anyLong(), anyLong(), anyString());
    }

    @Test
    void bodyMustBeOneToTwoHundredChars() {
        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        when(users.findById(2L)).thenReturn(Optional.of(virtual(true)));
        for (String bad : new String[] {null, "", "   ", "x".repeat(201)}) {
            assertThatThrownBy(() -> service.postAsVirtual(1L, 2L, bad, "k", 5L)).isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.virtualComment.bodyInvalid"));
        }
    }

    @Test
    void deletedOrHiddenPostIs404() {
        when(idempotency.findResourceId(any())).thenReturn(Optional.empty());
        when(users.findById(2L)).thenReturn(Optional.of(virtual(true)));
        when(posts.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.postAsVirtual(1L, 2L, "hi", "k", 5L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(404));
        ContentPost deleted = publishedPost();
        deleted.softDelete();
        when(posts.findById(1L)).thenReturn(Optional.of(deleted));
        assertThatThrownBy(() -> service.postAsVirtual(1L, 2L, "hi", "k", 5L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(404));
    }

    @Test
    void happyPathGoesThroughCommentServiceAndAuditsPreviewOnly() {
        when(idempotency.findResourceId("k9")).thenReturn(Optional.empty());
        when(users.findById(2L)).thenReturn(Optional.of(virtual(true)));
        when(posts.findById(1L)).thenReturn(Optional.of(publishedPost()));
        CommentResponse resp = new CommentResponse(123L, 2L, "MeowSisters", null, false, null, "x", Instant.now(), 0, List.of(), "UNDER_REVIEW");
        when(commentService.createTopLevel(1L, 2L, "好可爱的猫！".repeat(12))).thenReturn(resp);

        String longBody = "好可爱的猫！".repeat(12); // 72 字
        VirtualCommentService.Result r = service.postAsVirtual(1L, 2L, "  " + longBody + "  ", "k9", 5L);

        assertThat(r.commentId()).isEqualTo(123L);
        assertThat(r.replayed()).isFalse();
        verify(commentService).createTopLevel(1L, 2L, longBody);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(5L), eq(AuditActions.COMMENT_VIRTUAL_POST), eq("COMMENT"), eq("123"), summary.capture());
        assertThat(summary.getValue()).contains("postId=1").contains("virtualUserId=2").doesNotContain(longBody)
                .contains(longBody.substring(0, 50) + "…");
        verify(idempotency).store("k9", 123L);
        // AC3 重点：只经 CommentService 同链路，绝不直落 comments 仓储
        verify(comments, never()).save(any());
    }

    @Test
    void summarizeTruncatesAndCollapsesWhitespace() {
        assertThat(VirtualCommentService.summarize("  a  b\n c ", 80)).isEqualTo("a b c");
        assertThat(VirtualCommentService.summarize("x".repeat(90), 80)).hasSize(81).endsWith("…");
        assertThat(VirtualCommentService.summarize(null, 80)).isEmpty();
    }
}
