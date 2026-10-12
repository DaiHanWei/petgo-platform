package com.tailtopia.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.domain.UserFollow;
import com.tailtopia.social.dto.FollowListItem;
import com.tailtopia.social.dto.FollowListPage;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.social.repository.UserFollowRepository;
import com.tailtopia.social.repository.UserHideRelationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** L0：关注 / 粉丝列表组装（V1.3.4 follow · Story 2.2 · AC1）—— 整页批量、游客、拉黑、分页、注销行。 */
class FollowListQueryServiceTest {

    private static final String OWNER_TOKEN = "o".repeat(32);
    private static final long OWNER = 50L;
    private static final long VIEWER = 1L;

    private UserFollowRepository follows;
    private AccountQueryService accounts;
    private FollowReader followReader;
    private UserHideRelationReader hideRelations;
    private UserHideRelationRepository hideRepo;
    private FollowListQueryService service;

    @BeforeEach
    void setUp() {
        follows = mock(UserFollowRepository.class);
        accounts = mock(AccountQueryService.class);
        followReader = mock(FollowReader.class);
        hideRelations = mock(UserHideRelationReader.class);
        hideRepo = mock(UserHideRelationRepository.class);
        service = new FollowListQueryService(follows, accounts, followReader, hideRelations, hideRepo);
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(OWNER);
        when(accounts.findActiveByPublicToken(OWNER_TOKEN)).thenReturn(Optional.of(owner));
        when(accounts.findAuthorViewsWithoutTags(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().collect(Collectors.toMap(id -> id,
                    id -> id == 99L ? AuthorView.anonymized(id) : new AuthorView(id, "u" + id, null, false, List.of())));
        });
        when(accounts.publicTokensOf(anyCollection(), anyBoolean())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().filter(id -> id != 99L).collect(Collectors.toMap(id -> id, id -> "tok" + id));
        });
        when(followReader.followStates(anyLong(), anyCollection())).thenReturn(Map.of());
        when(hideRepo.findTargetIdsByHolderAndSourceIn(anyLong(), any(), anyList())).thenReturn(List.of());
    }

    private static UserFollow row(long follower, long followee, Instant at) {
        UserFollow f = mock(UserFollow.class);
        when(f.getFollowerId()).thenReturn(follower);
        when(f.getFolloweeId()).thenReturn(followee);
        when(f.getCreatedAt()).thenReturn(at);
        return f;
    }

    private void followingRows(int n) {
        List<UserFollow> rows = new ArrayList<>();
        Instant t = Instant.parse("2026-10-12T00:00:00Z");
        for (int i = 0; i < n; i++) {
            rows.add(row(OWNER, 100L + i, t.minusSeconds(i)));
        }
        when(follows.findFollowingPage(eq(OWNER), any(), anyLong(), anyInt())).thenReturn(rows);
    }

    @Test
    void pageOf21RowsReturns20WithCursorAndBatchesEachLookupOnce() {
        followingRows(21);

        FollowListPage page = service.following(OWNER_TOKEN, VIEWER, null);

        assertThat(page.items()).hasSize(20);
        assertThat(page.hasMore()).isTrue();
        KeysetCursor next = KeysetCursor.decodeOrNull(page.nextCursor());
        assertThat(next.id()).isEqualTo(119L);
        verify(accounts, times(1)).findAuthorViewsWithoutTags(anyCollection());
        verify(accounts, times(1)).publicTokensOf(anyCollection(), eq(true));
        verify(followReader, times(1)).followStates(eq(VIEWER), anyCollection());
        verify(hideRepo, times(1)).findTargetIdsByHolderAndSourceIn(eq(VIEWER), eq(HideSource.BLOCK), anyList());
        verify(followReader, never()).followState(anyLong(), anyLong());
    }

    @Test
    void relationFieldsAreRelativeToViewer() {
        List<UserFollow> rows = List.of(
                row(OWNER, 100L, Instant.parse("2026-10-12T00:00:03Z")),
                row(OWNER, VIEWER, Instant.parse("2026-10-12T00:00:02Z")),
                row(OWNER, 101L, Instant.parse("2026-10-12T00:00:01Z")),
                row(OWNER, 99L, Instant.parse("2026-10-12T00:00:00Z")));
        when(follows.findFollowingPage(eq(OWNER), any(), anyLong(), anyInt())).thenReturn(rows);
        when(followReader.followStates(eq(VIEWER), anyCollection()))
                .thenReturn(Map.of(100L, new FollowState(true, true)));
        when(hideRepo.findTargetIdsByHolderAndSourceIn(eq(VIEWER), eq(HideSource.BLOCK), anyList()))
                .thenReturn(List.of(101L));

        List<FollowListItem> items = service.following(OWNER_TOKEN, VIEWER, null).items();

        assertThat(items.get(0)).isEqualTo(new FollowListItem(100L, "tok100", "u100", null, false, true, true, false, false));
        assertThat(items.get(1).self()).isTrue();
        assertThat(items.get(2).blockedByMe()).isTrue();
        // 注销行：身份字段与 token 都不下发。
        assertThat(items.get(3)).isEqualTo(new FollowListItem(99L, null, null, null, true, false, false, false, false));
    }

    @Test
    void guestGetsAllFalseAndNoRelationQueries() {
        followingRows(3);

        FollowListPage page = service.following(OWNER_TOKEN, null, null);

        assertThat(page.items()).allSatisfy(i -> {
            assertThat(i.iFollow()).isFalse();
            assertThat(i.followsMe()).isFalse();
            assertThat(i.blockedByMe()).isFalse();
            assertThat(i.self()).isFalse();
        });
        verify(followReader, never()).followStates(anyLong(), anyCollection());
        verify(hideRepo, never()).findTargetIdsByHolderAndSourceIn(anyLong(), any(), anyList());
    }

    /** D-10：我拉黑了主页用户 → 403 blocked-user；对方拉黑了我照常返回。 */
    @Test
    void viewerWhoBlockedOwnerGets403() {
        when(hideRelations.isBlocked(VIEWER, OWNER)).thenReturn(true);
        assertThatThrownBy(() -> service.followers(OWNER_TOKEN, VIEWER, null))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.BLOCKED_USER);
                });

        when(hideRelations.isBlocked(VIEWER, OWNER)).thenReturn(false);
        when(hideRelations.isBlocked(OWNER, VIEWER)).thenReturn(true);
        when(follows.findFollowersPage(eq(OWNER), any(), anyLong(), anyInt())).thenReturn(List.of());
        assertThat(service.followers(OWNER_TOKEN, VIEWER, null).items()).isEmpty();
    }

    @Test
    void unknownOwnerIs404() {
        assertThatThrownBy(() -> service.following("x".repeat(32), VIEWER, null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void badCursorFallsBackToFirstPageAndValidCursorIsPassedThrough() {
        followingRows(0);
        service.following(OWNER_TOKEN, VIEWER, "@@not-base64@@");
        var ts = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var id = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(follows).findFollowingPage(eq(OWNER), ts.capture(), id.capture(), anyInt());
        assertThat(id.getValue()).isEqualTo(Long.MAX_VALUE);
        assertThat(ts.getValue()).isAfter(Instant.now());

        Instant at = Instant.parse("2026-10-01T00:00:00.123456Z");
        service.following(OWNER_TOKEN, VIEWER, new KeysetCursor(at, 42L).encode());
        verify(follows).findFollowingPage(OWNER, at, 42L, 21);
    }

    @Test
    void emptyPageIssuesNoLookups() {
        followingRows(0);
        FollowListPage page = service.following(OWNER_TOKEN, VIEWER, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        verify(accounts, never()).findAuthorViewsWithoutTags(anyCollection());
        verify(followReader, never()).followStates(anyLong(), anyCollection());
    }
}
