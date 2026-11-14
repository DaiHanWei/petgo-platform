package com.tailtopia.profile.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.social.event.PetFollowedEvent;
import com.tailtopia.social.event.UserFollowedEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 onboarding-recall Story 2.1 · AC3 ~ AC6 —— 新手任务①–⑥的记录时机与条件。 */
class NewbieTaskListenerTest {

    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00Z");

    private NewbieMarkService marks;
    private NewbieTaskListener listener;

    @BeforeEach
    void setUp() {
        marks = mock(NewbieMarkService.class);
        listener = new NewbieTaskListener(marks);
    }

    private static ContentPublishedEvent published(ContentType type, ContentVisibility v) {
        return new ContentPublishedEvent(1L, 10L, type, null, 1L, v, NOW, List.of(), List.of());
    }

    @Test
    void 发Diary记作者第一篇Diary_私密也算() {
        listener.onContentPublished(published(ContentType.GROWTH_MOMENT, ContentVisibility.PRIVATE));
        verify(marks).markIfAbsent(10L, NewbieTask.FIRST_DIARY);
    }

    @Test
    void Moment不记第一篇Diary() {
        listener.onContentPublished(published(ContentType.DAILY, ContentVisibility.PUBLIC));
        verifyNoInteractions(marks);
    }

    @Test
    void 给别人点赞双方各记一项() {
        listener.onContentLiked(new ContentLikedEvent(1L, 5L, 10L, NOW));
        verify(marks).markIfAbsent(5L, NewbieTask.FIRST_LIKE_GIVEN);
        verify(marks).markIfAbsent(10L, NewbieTask.FIRST_LIKE_RECEIVED);
    }

    @Test
    void 自赞两项都不记() {
        listener.onContentLiked(new ContentLikedEvent(1L, 10L, 10L, NOW));
        verifyNoInteractions(marks);
    }

    @Test
    void 一级评论别人帖子记第一条评论() {
        listener.onContentCommented(new ContentCommentedEvent(1L, 2L, 5L, 10L, null, NOW, null));
        verify(marks).markIfAbsent(5L, NewbieTask.FIRST_COMMENT);
        verify(marks, never()).markIfAbsent(anyLong(), org.mockito.ArgumentMatchers.eq(NewbieTask.FIRST_REPLY));
    }

    @Test
    void 自己帖子下一级评论不记() {
        listener.onContentCommented(new ContentCommentedEvent(1L, 2L, 10L, 10L, null, NOW, null));
        verifyNoInteractions(marks);
    }

    @Test
    void 回复别人的评论记回复不记评论_含自己帖子下() {
        listener.onContentCommented(new ContentCommentedEvent(1L, 3L, 10L, 10L, 5L, NOW, 2L));
        verify(marks).markIfAbsent(10L, NewbieTask.FIRST_REPLY);
        verify(marks, never()).markIfAbsent(anyLong(), org.mockito.ArgumentMatchers.eq(NewbieTask.FIRST_COMMENT));
    }

    @Test
    void 回复自己的评论不记() {
        listener.onContentCommented(new ContentCommentedEvent(1L, 3L, 5L, 10L, 5L, NOW, 2L));
        verifyNoInteractions(marks);
    }

    @Test
    void 被回复人以replyToAuthorId优先() {
        // 一级评论是自己的（parentAuthor = 5），但回复的是楼中楼里别人（replyTo = 8）→ 算回复别人。
        listener.onContentCommented(new ContentCommentedEvent(1L, 3L, 5L, 10L, 5L, NOW, 2L, null, 8L));
        verify(marks).markIfAbsent(5L, NewbieTask.FIRST_REPLY);
        // 反过来：一级评论是别人的，但回复的楼中楼是自己 → 不算。
        NewbieMarkService m2 = mock(NewbieMarkService.class);
        new NewbieTaskListener(m2).onContentCommented(
                new ContentCommentedEvent(1L, 4L, 5L, 10L, 8L, NOW, 2L, null, 5L));
        verifyNoInteractions(m2);
    }

    @Test
    void 关注人或追更宠物都记关注() {
        listener.onUserFollowed(new UserFollowedEvent(5L, 6L, NOW));
        listener.onPetFollowed(new PetFollowedEvent(7L, 100L, 6L, NOW));
        verify(marks).markIfAbsent(5L, NewbieTask.FIRST_FOLLOW);
        verify(marks).markIfAbsent(7L, NewbieTask.FIRST_FOLLOW);
    }

    @Test
    void 写入失败只记日志不外抛() {
        when(marks.markIfAbsent(anyLong(), any())).thenThrow(new IllegalStateException("db"));
        listener.onUserFollowed(new UserFollowedEvent(5L, 6L, NOW));
    }

    @Test
    void 监听不按automated过滤() throws Exception {
        // 跨线约定：自动点赞计入②。源码里不得出现 automated 判断。
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/tailtopia/profile/service/NewbieTaskListener.java"));
        String code = src.lines().filter(l -> !l.stripLeading().startsWith("*") && !l.stripLeading().startsWith("//"))
                .reduce("", (a, b) -> a + "\n" + b);
        org.assertj.core.api.Assertions.assertThat(code).doesNotContain("automated");
    }
}
