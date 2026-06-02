package com.petgo.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.petgo.auth.dto.AuthorView;
import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.domain.Comment;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.dto.CommentPageResponse;
import com.petgo.content.repository.CommentRepository;
import com.petgo.content.repository.ContentPostRepository;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/** L0：一级分页 10/批 + hasMore、内嵌前 3 二级 + replyCount、回复展开、帖不可见 404（AC3/AC4）。 */
class CommentQueryServiceTest {

    private CommentRepository comments;
    private ContentPostRepository posts;
    private AccountQueryService accounts;
    private CommentQueryService service;

    @BeforeEach
    void setUp() {
        comments = mock(CommentRepository.class);
        posts = mock(ContentPostRepository.class);
        accounts = mock(AccountQueryService.class);
        service = new CommentQueryService(comments, posts, accounts);
        // 帖默认可见。
        when(posts.findById(anyLong())).thenReturn(Optional.of(visiblePost()));
        // 作者投影：按 id 给非注销视图。
        when(accounts.findAuthorViews(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().distinct().collect(Collectors.toMap(
                    id -> (Long) id, id -> new AuthorView((Long) id, "u" + id, null, false)));
        });
    }

    private static ContentPost visiblePost() {
        ContentPost p = ContentPost.publish(7L, ContentType.DAILY, null, "x", null);
        setField(p, ContentPost.class, "id", 1L);
        setField(p, ContentPost.class, "createdAt", Instant.now());
        return p;
    }

    private static Comment comment(long id, Long parentId, long authorId, Instant ts) {
        Comment c = newComment();
        setField(c, Comment.class, "id", id);
        setField(c, Comment.class, "postId", 1L);
        setField(c, Comment.class, "parentId", parentId);
        setField(c, Comment.class, "authorId", authorId);
        setField(c, Comment.class, "body", "c" + id);
        setField(c, Comment.class, "createdAt", ts);
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
    void topLevelPaginatesTenAndFlagsHasMore() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        // 11 条一级 → hasMore=true，截 10。
        List<Comment> rows = IntStream.range(0, 11)
                .mapToObj(i -> comment(i + 1, null, 100 + i, base.plusSeconds(i)))
                .toList();
        when(comments.findTopLevel(eq(1L), any(), any(), any(Pageable.class))).thenReturn(rows);
        when(comments.findRepliesForParents(anyList())).thenReturn(List.of());

        CommentPageResponse page = service.topLevel(1L, null);
        assertThat(page.items()).hasSize(10);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotNull();
        // 一级评论 replyCount=0、replies 空。
        assertThat(page.items().get(0).replyCount()).isZero();
        assertThat(page.items().get(0).replies()).isEmpty();
    }

    @Test
    void topLevelInlinesFirstThreeRepliesWithCount() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        Comment top = comment(1, null, 50, base);
        when(comments.findTopLevel(eq(1L), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(top));
        // 该一级有 8 条二级回复。
        List<Comment> replies = IntStream.range(0, 8)
                .mapToObj(i -> comment(100 + i, 1L, 200 + i, base.plusSeconds(i + 1)))
                .toList();
        when(comments.findRepliesForParents(anyList())).thenReturn(replies);

        CommentPageResponse page = service.topLevel(1L, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).replyCount()).isEqualTo(8); // 总数
        assertThat(page.items().get(0).replies()).hasSize(3); // 内嵌前 3
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void repliesExpansionPaginates() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        List<Comment> rows = IntStream.range(0, 11)
                .mapToObj(i -> comment(100 + i, 1L, 200 + i, base.plusSeconds(i)))
                .toList();
        when(comments.findReplies(eq(1L), any(), any(), any(Pageable.class))).thenReturn(rows);

        CommentPageResponse page = service.replies(1L, null);
        assertThat(page.items()).hasSize(10);
        assertThat(page.hasMore()).isTrue();
        // 二级回复 replyCount/replies 为 null。
        assertThat(page.items().get(0).replyCount()).isNull();
        assertThat(page.items().get(0).replies()).isNull();
    }

    @Test
    void commentsOnInvisiblePostAreNotFound() {
        when(posts.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.topLevel(99L, null)).isInstanceOf(AppException.class);
    }
}
