package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentDetailResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：详情读取 + 多态（404 不存在/软删；注销作者 200 匿名化）+ isAuthor（AC1/AC4）。 */
class ContentDetailServiceTest {

    private ContentPostRepository posts;
    private CommentRepository comments;
    private ContentLikeRepository likes;
    private AccountQueryService accounts;
    private ReportService reportService;
    private ContentDetailService service;

    @BeforeEach
    void setUp() {
        posts = mock(ContentPostRepository.class);
        comments = mock(CommentRepository.class);
        likes = mock(ContentLikeRepository.class);
        accounts = mock(AccountQueryService.class);
        reportService = mock(ReportService.class); // 默认 hasReported → false（未举报）
        service = new ContentDetailService(posts, comments, likes, accounts, reportService);
        when(comments.countVisibleForViewer(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any())).thenReturn(5L);
        when(likes.countByPostId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(2L);
    }

    private static ContentPost post(long id, long authorId, Instant deletedAt) {
        ContentPost p = ContentPost.publish(authorId, ContentType.DAILY, null, "body",
                List.of("https://cdn/a.jpg", "https://cdn/b.jpg"));
        set(p, "id", id);
        set(p, "createdAt", Instant.now());
        if (deletedAt != null) set(p, "deletedAt", deletedAt);
        return p;
    }

    private static void set(ContentPost p, String field, Object value) {
        try {
            var f = ContentPost.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(p, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void returnsDetailWithCommentCountAndImagesInOrder() {
        when(posts.findById(1L)).thenReturn(Optional.of(post(1L, 7L, null)));
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(7L, new AuthorView(7L, "Alice", "https://cdn/av.jpg", false)));

        ContentDetailResponse d = service.getDetail(1L, 7L);
        assertThat(d.imageUrls()).containsExactly("https://cdn/a.jpg", "https://cdn/b.jpg");
        assertThat(d.commentCount()).isEqualTo(5L);
        assertThat(d.likeCount()).isEqualTo(2L); // Story 3.4 真实计数
        assertThat(d.liked()).isFalse(); // 未 stub existsBy → 未赞
        assertThat(d.isAuthor()).isTrue(); // viewer == author
        assertThat(d.authorNickname()).isEqualTo("Alice");
    }

    @Test
    void guestIsNotAuthor() {
        when(posts.findById(1L)).thenReturn(Optional.of(post(1L, 7L, null)));
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(7L, new AuthorView(7L, "Alice", null, false)));
        assertThat(service.getDetail(1L, null).isAuthor()).isFalse();
    }

    @Test
    void missingPostIsNotFound() {
        when(posts.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDetail(99L, null)).isInstanceOf(AppException.class);
    }

    @Test
    void softDeletedPostIsNotFound() {
        when(posts.findById(2L)).thenReturn(Optional.of(post(2L, 7L, Instant.now())));
        assertThatThrownBy(() -> service.getDetail(2L, null)).isInstanceOf(AppException.class);
    }

    @Test
    void reporterSeesNotFoundForReportedPost() {
        // 内容审核 cm-6 §5.4：举报者对该帖视同不可见 → 404（即使帖仍 PUBLISHED）。
        when(posts.findById(4L)).thenReturn(Optional.of(post(4L, 7L, null)));
        when(reportService.hasReported(4L, 88L)).thenReturn(true);
        assertThatThrownBy(() -> service.getDetail(4L, 88L)).isInstanceOf(AppException.class);
    }

    private static ContentPost underReview(long id, long authorId) {
        ContentPost p = post(id, authorId, null);
        set(p, "status", com.tailtopia.content.domain.PostStatus.UNDER_REVIEW);
        return p;
    }

    @Test
    void authorSeesOwnUnderReviewPost() {
        // 内容审核 D-CM2：审核中作者无感知——本人可正常点入自己的挂起帖详情。
        when(posts.findById(5L)).thenReturn(Optional.of(underReview(5L, 7L)));
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(7L, new AuthorView(7L, "Alice", null, false)));
        ContentDetailResponse d = service.getDetail(5L, 7L);
        assertThat(d.isAuthor()).isTrue();
    }

    @Test
    void othersGetNotFoundForUnderReviewPost() {
        // 挂起帖对他人（含游客）零泄漏 → 统一 404 防枚举。
        when(posts.findById(5L)).thenReturn(Optional.of(underReview(5L, 7L)));
        assertThatThrownBy(() -> service.getDetail(5L, 88L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.getDetail(5L, null)).isInstanceOf(AppException.class);
    }

    @Test
    void deletedAuthorStillReturns200Anonymized() {
        when(posts.findById(3L)).thenReturn(Optional.of(post(3L, 8L, null)));
        when(accounts.findAuthorViews(anyList())).thenReturn(Map.of(8L, AuthorView.anonymized(8L)));

        ContentDetailResponse d = service.getDetail(3L, null);
        assertThat(d.authorDeleted()).isTrue(); // 非 404，匿名化保留
        assertThat(d.authorNickname()).isNull();
        assertThat(d.authorId()).isEqualTo(8L);
    }
}
