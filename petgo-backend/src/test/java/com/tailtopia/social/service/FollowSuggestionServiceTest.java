package com.tailtopia.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.social.dto.FollowListItem;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.repository.UserFollowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：推荐关注组装（V1.3.4 follow · Story 2.3 · AC1）—— SQL 次序保留、批量补字段、恒 false 字段、窗口与上限。 */
class FollowSuggestionServiceTest {

    private static final long VIEWER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");

    private UserFollowRepository follows;
    private AccountQueryService accounts;
    private FollowReader followReader;
    private FollowSuggestionService service;

    @BeforeEach
    void setUp() {
        follows = mock(UserFollowRepository.class);
        accounts = mock(AccountQueryService.class);
        followReader = mock(FollowReader.class);
        service = new FollowSuggestionService(follows, accounts, followReader,
                mock(com.tailtopia.profile.repository.PetProfileRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));
        when(accounts.findAuthorViewsWithoutTags(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().collect(Collectors.toMap(id -> id,
                    id -> new AuthorView(id, "u" + id, "https://a/" + id, false, List.of())));
        });
        when(accounts.publicTokensOf(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return ids.stream().filter(id -> id != 66L).collect(Collectors.toMap(id -> id, id -> "tok" + id));
        });
        when(followReader.followStates(anyLong(), anyCollection())).thenReturn(Map.of(30L, new FollowState(false, true)));
    }

    @Test
    void passesThirtyDayWindowAndLimitThirtyToTheQuery() {
        when(follows.findSuggestionIds(anyLong(), org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(List.of());

        service.suggestionsFor(VIEWER);

        verify(follows).findSuggestionIds(VIEWER, Instant.parse("2026-09-12T05:00:00Z"), 30);
    }

    @Test
    void emptyCandidatesSkipBatchLookups() {
        when(follows.findSuggestionIds(anyLong(), org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(List.of());

        assertThat(service.suggestionsFor(VIEWER).items()).isEmpty();
        verifyNoInteractions(accounts, followReader);
    }

    @Test
    void keepsSqlOrderAndFillsRowsWithOneBatchEach() {
        when(follows.findSuggestionIds(anyLong(), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(List.of(30L, 10L, 20L));

        List<FollowListItem> items = service.suggestionsFor(VIEWER).items();

        assertThat(items).extracting(FollowListItem::userId).containsExactly(30L, 10L, 20L);
        FollowListItem first = items.get(0);
        assertThat(first.userToken()).isEqualTo("tok30");
        assertThat(first.nickname()).isEqualTo("u30");
        assertThat(first.avatarUrl()).isEqualTo("https://a/30");
        assertThat(first.followsMe()).isTrue();
        assertThat(items.get(1).followsMe()).isFalse();
        for (FollowListItem i : items) {
            assertThat(i.iFollow()).isFalse();
            assertThat(i.self()).isFalse();
            assertThat(i.blockedByMe()).isFalse();
            assertThat(i.deleted()).isFalse();
        }
        verify(accounts, times(1)).findAuthorViewsWithoutTags(anyCollection());
        verify(accounts, times(1)).publicTokensOf(anyCollection());
        verify(accounts, never()).publicTokensOf(anyCollection(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(followReader, times(1)).followStates(eq(VIEWER), anyCollection());
    }

    /** 查询与补字段之间对方恰好注销 / 封号 → 拿不到 token，丢掉该行（没 token 无法关注）。 */
    @Test
    void dropsRowsWhoseTokenVanished() {
        when(follows.findSuggestionIds(anyLong(), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(List.of(10L, 66L, 20L));

        assertThat(service.suggestionsFor(VIEWER).items()).extracting(FollowListItem::userId)
                .containsExactly(10L, 20L);
    }
}
