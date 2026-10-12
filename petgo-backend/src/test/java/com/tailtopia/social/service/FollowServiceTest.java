package com.tailtopia.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.auth.service.FollowCounterService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.social.dto.FollowResultResponse;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.FollowState;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.social.repository.UserFollowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * L0：关注服务（V1.3.4 follow · Story 1.1 · AC9.2）—— 每日 500 上限、WIB 切日、拒绝顺序与条件计数。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FollowServiceTest {

    private static final long ME = 1L;
    private static final long TARGET = 2L;
    private static final String TOKEN = "t".repeat(32);

    @Mock UserFollowRepository follows;
    @Mock FollowCounterService counters;
    @Mock AccountQueryService accounts;
    @Mock UserHideRelationReader hideRelations;
    @Mock FollowReader followReader;
    @Mock RedisRateLimiter rateLimiter;

    private FollowService service(Instant now) {
        return new FollowService(follows, counters, accounts, hideRelations, followReader, rateLimiter,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void targetExists() {
        User target = Mockito.mock(User.class);
        when(target.getId()).thenReturn(TARGET);
        when(accounts.findActiveByPublicToken(TOKEN)).thenReturn(Optional.of(target));
        when(accounts.findByPublicToken(TOKEN)).thenReturn(Optional.of(target));
        when(followReader.followState(anyLong(), anyLong())).thenReturn(FollowState.NONE);
        when(accounts.isActive(ME)).thenReturn(true);
    }

    @Test
    void dailyCountAt500Is429AndNothingWritten() {
        targetExists();
        when(follows.countCreatedSince(eq(ME), any())).thenReturn(500L);

        assertThatThrownBy(() -> service(Instant.parse("2026-10-12T05:00:00Z")).follow(ME, TOKEN))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.RATE_LIMITED);
                });
        verify(follows, never()).insertIfAbsent(anyLong(), anyLong());
        verify(counters, never()).applyFollow(anyLong(), anyLong());
    }

    @Test
    void dailyCountBelow500Follows() {
        targetExists();
        when(follows.countCreatedSince(eq(ME), any())).thenReturn(499L);
        when(follows.insertIfAbsent(ME, TARGET)).thenReturn(1);
        when(counters.followerCountOf(TARGET)).thenReturn(7L);

        FollowResultResponse r = service(Instant.parse("2026-10-12T05:00:00Z")).follow(ME, TOKEN);

        assertThat(r).isEqualTo(new FollowResultResponse(true, false, 7L));
        verify(counters).applyFollow(ME, TARGET);
    }

    /** WIB = UTC+7：UTC 16:59 仍是 WIB 当天 23:59，窗口起点是当天 WIB 00:00（= 前一天 UTC 17:00）。 */
    @Test
    void wibDayWindowBeforeMidnight() {
        targetExists();
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        when(follows.countCreatedSince(eq(ME), since.capture())).thenReturn(0L);

        service(Instant.parse("2026-10-12T16:59:00Z")).follow(ME, TOKEN);

        assertThat(since.getValue()).isEqualTo(Instant.parse("2026-10-11T17:00:00Z"));
    }

    /** UTC 17:00 = WIB 次日 00:00：窗口切到新的一天。 */
    @Test
    void wibDayWindowAtMidnight() {
        targetExists();
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        when(follows.countCreatedSince(eq(ME), since.capture())).thenReturn(0L);

        service(Instant.parse("2026-10-12T17:00:00Z")).follow(ME, TOKEN);

        assertThat(since.getValue()).isEqualTo(Instant.parse("2026-10-12T17:00:00Z"));
    }

    /** 已关注：不查每日上限、不写入、不改计数（幂等）。 */
    @Test
    void alreadyFollowingSkipsDailyLimitAndWrites() {
        targetExists();
        when(follows.existsByFollowerIdAndFolloweeId(ME, TARGET)).thenReturn(true);

        FollowResultResponse r = service(Instant.parse("2026-10-12T05:00:00Z")).follow(ME, TOKEN);

        assertThat(r.iFollow()).isTrue();
        verify(follows, never()).countCreatedSince(anyLong(), any());
        verify(follows, never()).insertIfAbsent(anyLong(), anyLong());
        verify(counters, never()).applyFollow(anyLong(), anyLong());
    }

    /** 并发双击：插入受影响 0 行 → 不改计数。 */
    @Test
    void concurrentInsertConflictDoesNotIncrement() {
        targetExists();
        when(follows.insertIfAbsent(ME, TARGET)).thenReturn(0);

        service(Instant.parse("2026-10-12T05:00:00Z")).follow(ME, TOKEN);

        verify(counters, never()).applyFollow(anyLong(), anyLong());
    }

    @Test
    void blockedTargetIs409FollowBlocked() {
        targetExists();
        when(hideRelations.isBlocked(ME, TARGET)).thenReturn(true);

        assertThatThrownBy(() -> service(Instant.now()).follow(ME, TOKEN))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.FOLLOW_BLOCKED);
                });
    }

    /** D-4：对方拉黑我不拦 —— 服务只问「我是否拉黑对方」。 */
    @Test
    void onlyViewerSideBlockIsChecked() {
        targetExists();
        when(hideRelations.isBlocked(TARGET, ME)).thenReturn(true);
        when(follows.insertIfAbsent(ME, TARGET)).thenReturn(1);

        service(Instant.now()).follow(ME, TOKEN);

        verify(counters).applyFollow(ME, TARGET);
    }

    @Test
    void unknownTargetIs404() {
        when(accounts.isActive(ME)).thenReturn(true);
        when(accounts.findActiveByPublicToken(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(Instant.now()).follow(ME, TOKEN))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** 注销 / 封号后旧 access token 仍有效：调用方本人无效 → 401，不写任何东西。 */
    @Test
    void inactiveViewerCannotFollow() {
        targetExists();
        when(accounts.isActive(ME)).thenReturn(false);

        assertThatThrownBy(() -> service(Instant.now()).follow(ME, TOKEN))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(follows, never()).insertIfAbsent(anyLong(), anyLong());
    }

    @Test
    void selfIs422() {
        when(accounts.isActive(ME)).thenReturn(true);
        User self = Mockito.mock(User.class);
        when(self.getId()).thenReturn(ME);
        when(accounts.findActiveByPublicToken(TOKEN)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> service(Instant.now()).follow(ME, TOKEN))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void unfollowOnlyDecrementsWhenRowDeleted() {
        targetExists();
        when(follows.deleteRelation(ME, TARGET)).thenReturn(0);
        service(Instant.now()).unfollow(ME, TOKEN);
        verify(counters, never()).applyUnfollow(anyLong(), anyLong());

        when(follows.deleteRelation(ME, TARGET)).thenReturn(1);
        FollowResultResponse r = service(Instant.now()).unfollow(ME, TOKEN);
        verify(counters).applyUnfollow(ME, TARGET);
        assertThat(r.iFollow()).isFalse();
    }

    /** T-1：关注与取关共用一个限流桶。 */
    @Test
    void followAndUnfollowShareRateLimitBucket() {
        targetExists();
        service(Instant.now()).follow(ME, TOKEN);
        service(Instant.now()).unfollow(ME, TOKEN);
        verify(rateLimiter, Mockito.times(2)).check(eq("rl:social:follow:" + ME), eq(30), any());
    }

    /** AC7：注销清理先扣计数再删行。 */
    @Test
    void purgeUserDecrementsCountsBeforeDeletingRows() {
        service(Instant.now()).purgeUser(ME);
        var order = Mockito.inOrder(counters, follows);
        order.verify(counters).purgeCountsFor(ME);
        order.verify(follows).deleteAllInvolving(ME);
    }
}
