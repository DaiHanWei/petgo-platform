package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.FeedItemResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：顶置位的作者 token / 关注关系分支（V1.3.4 follow · Story 2.1 · code-review #6）。 */
class FeedPinnedFollowTest {

    private static final long AUTHOR = 7L;

    private ContentPostRepository posts;
    private AccountQueryService accounts;
    private FollowReader followReader;
    private FeedService service;

    @BeforeEach
    void setUp() {
        posts = mock(ContentPostRepository.class);
        accounts = mock(AccountQueryService.class);
        followReader = mock(FollowReader.class);
        ContentPinService pins = mock(ContentPinService.class);
        UserHideRelationReader hide = mock(UserHideRelationReader.class);
        service = new FeedService(posts, accounts, mock(ContentLikeRepository.class), mock(CommentRepository.class),
                pins, mock(ContentTagQueryService.class), hide,
                mock(com.tailtopia.content.rank.FeedRecommendationService.class),
                new com.tailtopia.mention.service.MentionViewService(mock(AccountQueryService.class), hide),
                followReader, com.tailtopia.content.service.PostMediaFixtures.passthrough());
        ContentPin pin = ContentPin.ofContent(ContentPin.SLOT_HOME_FEED, 42L,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(pin, "id", 1L);
        when(pins.activePin(any(), any())).thenReturn(Optional.of(pin));
        ContentPost post = ContentPost.publish(AUTHOR, ContentType.DAILY, null, "pinned", List.of());
        ReflectionTestUtils.setField(post, "id", 42L);
        ReflectionTestUtils.setField(post, "createdAt", Instant.now());
        when(posts.findById(42L)).thenReturn(Optional.of(post));
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(AUTHOR, new AuthorView(AUTHOR, "Alice", null, false, List.of())));
    }

    private FeedItemResponse pinnedItem(Long viewer) {
        return service.loadPinnedSlot(ContentPin.SLOT_HOME_FEED, viewer, true).pin().item();
    }

    @Test
    void otherViewerGetsTokenAndSingleFollowLookup() {
        when(accounts.publicTokensOf(anyCollection())).thenReturn(Map.of(AUTHOR, "tok7"));
        when(followReader.followState(99L, AUTHOR)).thenReturn(new FollowState(false, true));

        FeedItemResponse item = pinnedItem(99L);

        assertThat(item.authorToken()).isEqualTo("tok7");
        assertThat(item.authorFollowState()).isEqualTo(new FollowState(false, true));
    }

    @Test
    void guestAndAuthorDoNotQueryFollowState() {
        when(accounts.publicTokensOf(anyCollection())).thenReturn(Map.of(AUTHOR, "tok7"));

        assertThat(pinnedItem(null).authorFollowState()).isEqualTo(FollowState.NONE);
        assertThat(pinnedItem(AUTHOR).authorFollowState()).isEqualTo(FollowState.NONE);
        verify(followReader, never()).followState(anyLong(), anyLong());
    }

    /** D-8：封号作者 → 无 token、不查关注关系。 */
    @Test
    void deactivatedAuthorHasNoToken() {
        when(accounts.publicTokensOf(anyCollection())).thenReturn(Map.of());

        FeedItemResponse item = pinnedItem(99L);

        assertThat(item.authorToken()).isNull();
        assertThat(item.authorFollowState()).isEqualTo(FollowState.NONE);
        verify(followReader, never()).followState(anyLong(), anyLong());
    }
}
