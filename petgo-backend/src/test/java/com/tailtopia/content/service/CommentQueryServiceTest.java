package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.CommentPageResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
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
    private UserHideRelationReader hideRelations;
    private CommentQueryService service;

    @BeforeEach
    void setUp() {
        comments = mock(CommentRepository.class);
        posts = mock(ContentPostRepository.class);
        accounts = mock(AccountQueryService.class);
        // Story 1.3：默认无任何隐藏关系（isHidden → false），既有四个用例语义保持不变。
        hideRelations = mock(UserHideRelationReader.class);
        service = new CommentQueryService(comments, posts, accounts, hideRelations);
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
        when(comments.findTopLevel(eq(1L), anyBoolean(), any(), any(), anyBoolean(), any(),
                anyLong(), any(Pageable.class)))
                .thenReturn(rows);
        when(comments.findRepliesForParents(anyList(), anyBoolean(), any(), anyLong())).thenReturn(List.of());

        CommentPageResponse page = service.topLevel(1L, null, null);
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
        when(comments.findTopLevel(eq(1L), anyBoolean(), any(), any(), anyBoolean(), any(),
                anyLong(), any(Pageable.class)))
                .thenReturn(List.of(top));
        // 该一级有 8 条二级回复。
        List<Comment> replies = IntStream.range(0, 8)
                .mapToObj(i -> comment(100 + i, 1L, 200 + i, base.plusSeconds(i + 1)))
                .toList();
        when(comments.findRepliesForParents(anyList(), anyBoolean(), any(), anyLong())).thenReturn(replies);

        CommentPageResponse page = service.topLevel(1L, null, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).replyCount()).isEqualTo(8); // 总数
        assertThat(page.items().get(0).replies()).hasSize(3); // 内嵌前 3
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void repliesExpansionPaginates() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        // Story 1.3：replies 分支现在要先反查父评论（拿 postId → postAuthorId 供 R2 用）。
        when(comments.findById(1L)).thenReturn(Optional.of(comment(1, null, 50, base)));
        List<Comment> rows = IntStream.range(0, 11)
                .mapToObj(i -> comment(100 + i, 1L, 200 + i, base.plusSeconds(i)))
                .toList();
        when(comments.findReplies(eq(1L), anyBoolean(), any(), any(), anyBoolean(), any(),
                anyLong(), any(Pageable.class)))
                .thenReturn(rows);

        CommentPageResponse page = service.replies(1L, null, null);
        assertThat(page.items()).hasSize(10);
        assertThat(page.hasMore()).isTrue();
        // 二级回复 replyCount/replies 为 null。
        assertThat(page.items().get(0).replyCount()).isNull();
        assertThat(page.items().get(0).replies()).isNull();
    }

    @Test
    void commentsOnInvisiblePostAreNotFound() {
        when(posts.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.topLevel(99L, null, null)).isInstanceOf(AppException.class);
    }

    // ===== V1.1.4 Story 1.3：R1 / R2 在 service 这一层的两件事 =====
    // （SQL 本身的正确性由 L1 的 CommentHideFilterIntegrationTest 三视角验，这里只验参数与父闸门）

    /** R2 的判据是**内容作者**，所以 postAuthorId 必须真的被传进查询——传错这一个参数，整条 R2 形同虚设。 */
    @Test
    void topLevelPassesPostAuthorIdAndViewerFlagIntoQuery() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        when(comments.findTopLevel(eq(1L), anyBoolean(), any(), any(), anyBoolean(), any(),
                anyLong(), any(Pageable.class))).thenReturn(List.of(comment(1, null, 50, base)));
        when(comments.findRepliesForParents(anyList(), anyBoolean(), any(), anyLong()))
                .thenReturn(List.of());

        service.topLevel(1L, null, 88L);

        // visiblePost() 的作者是 7；hasViewer 随 viewerId 是否为 null 走。
        verify(comments).findTopLevel(eq(1L), anyBoolean(), any(), any(), eq(true), eq(88L),
                eq(7L), any(Pageable.class));
        verify(comments).findRepliesForParents(anyList(), eq(true), eq(88L), eq(7L));
    }

    /** 游客：hasViewer=false（不能靠裸 `:viewerId IS NULL` 判空，PG 会 42P18）。 */
    @Test
    void topLevelMarksGuestWithHasViewerFalse() {
        when(comments.findTopLevel(eq(1L), anyBoolean(), any(), any(), anyBoolean(), any(),
                anyLong(), any(Pageable.class))).thenReturn(List.of());

        service.topLevel(1L, null, null);

        verify(comments).findTopLevel(eq(1L), anyBoolean(), any(), any(), eq(false), eq(null),
                eq(7L), any(Pageable.class));
    }

    /** AC4：父被我拉黑（R1）→ 整串不展示，连查都不查。 */
    @Test
    void repliesReturnEmptyWhenParentAuthorHiddenByViewer() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        when(comments.findById(1L)).thenReturn(Optional.of(comment(1, null, 50, base)));
        when(hideRelations.isHidden(88L, 50L)).thenReturn(true); // 查看者 88 隐藏了父作者 50

        CommentPageResponse page = service.replies(1L, null, 88L);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        verify(comments, never()).findReplies(anyLong(), anyBoolean(), any(), any(), anyBoolean(),
                any(), anyLong(), any(Pageable.class));
    }

    /** AC4：父被内容作者影子（R2）→ 第三方展开也是空的。 */
    @Test
    void repliesReturnEmptyWhenParentAuthorShadowedByPostAuthor() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        when(comments.findById(1L)).thenReturn(Optional.of(comment(1, null, 50, base)));
        when(hideRelations.isHidden(7L, 50L)).thenReturn(true); // 内容作者 7 隐藏了父作者 50

        assertThat(service.replies(1L, null, 88L).items()).isEmpty();
    }

    /**
     * ⚠️ AC3 最易做反的一条：被影子的人**自己**展开自己那条，必须照常拿到回复。
     * 把 R2 写成与 R1 对称（不放过 c.authorId = :viewerId）时，这条会红。
     */
    @Test
    void shadowedAuthorStillSeesRepliesToHisOwnComment() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        when(comments.findById(1L)).thenReturn(Optional.of(comment(1, null, 50, base)));
        when(hideRelations.isHidden(7L, 50L)).thenReturn(true); // 内容作者影子了他
        when(comments.findReplies(eq(1L), anyBoolean(), any(), any(), anyBoolean(), any(),
                anyLong(), any(Pageable.class)))
                .thenReturn(List.of(comment(100, 1L, 200, base.plusSeconds(1))));

        // viewer 就是父评论作者本人（50）
        CommentPageResponse page = service.replies(1L, null, 50L);

        assertThat(page.items()).hasSize(1);
    }

    /** 父已被删 / 不存在 → 空页，不抛异常（展开端点原本就不校验帖子可见性，本 story 不改这一点）。 */
    @Test
    void repliesReturnEmptyWhenParentMissing() {
        when(comments.findById(9L)).thenReturn(Optional.empty());
        assertThat(service.replies(9L, null, 88L).items()).isEmpty();
    }
}
