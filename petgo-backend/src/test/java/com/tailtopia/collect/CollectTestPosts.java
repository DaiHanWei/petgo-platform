package com.tailtopia.collect;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import java.time.Instant;

/** 收藏测试用的帖子桩（V1.4.0 ④ Story 2.1）：只桩可见性判断会读的几个 getter。 */
public final class CollectTestPosts {

    private CollectTestPosts() {
    }

    public static ContentPost post(long id, long authorId, PostStatus status, ContentVisibility visibility,
            boolean deleted) {
        ContentPost p = mock(ContentPost.class);
        lenient().when(p.getId()).thenReturn(id);
        lenient().when(p.getAuthorId()).thenReturn(authorId);
        lenient().when(p.getStatus()).thenReturn(status);
        lenient().when(p.getVisibility()).thenReturn(visibility);
        lenient().when(p.getDeletedAt()).thenReturn(deleted ? Instant.parse("2026-10-18T00:00:00Z") : null);
        return p;
    }

    public static ContentPost published(long id, long authorId) {
        return post(id, authorId, PostStatus.PUBLISHED, ContentVisibility.PUBLIC, false);
    }
}
