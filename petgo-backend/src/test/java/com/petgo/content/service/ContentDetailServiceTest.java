package com.petgo.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.petgo.auth.dto.AuthorView;
import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.dto.ContentDetailResponse;
import com.petgo.content.repository.CommentRepository;
import com.petgo.content.repository.ContentLikeRepository;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.shared.error.AppException;
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
    private ContentDetailService service;

    @BeforeEach
    void setUp() {
        posts = mock(ContentPostRepository.class);
        comments = mock(CommentRepository.class);
        likes = mock(ContentLikeRepository.class);
        accounts = mock(AccountQueryService.class);
        service = new ContentDetailService(posts, comments, likes, accounts);
        when(comments.countByPostIdAndDeletedAtIsNull(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(5L);
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
    void deletedAuthorStillReturns200Anonymized() {
        when(posts.findById(3L)).thenReturn(Optional.of(post(3L, 8L, null)));
        when(accounts.findAuthorViews(anyList())).thenReturn(Map.of(8L, AuthorView.anonymized(8L)));

        ContentDetailResponse d = service.getDetail(3L, null);
        assertThat(d.authorDeleted()).isTrue(); // 非 404，匿名化保留
        assertThat(d.authorNickname()).isNull();
        assertThat(d.authorId()).isEqualTo(8L);
    }
}
