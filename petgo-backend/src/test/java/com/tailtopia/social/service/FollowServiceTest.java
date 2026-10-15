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
import com.tailtopia.social.event.UserFollowedEvent;
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
import org.springframework.context.ApplicationEventPublisher;
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
    @Mock ApplicationEventPublisher events;
    @Mock com.tailtopia.social.repository.OfficialFollowOptoutRepository optouts;

    private FollowService service(Instant now) {
        return new FollowService(follows, counters, accounts, hideRelations, followReader, rateLimiter, events,
                optouts, Clock.fixed(now, ZoneOffset.UTC));
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

    // ===== V1.3.4 follow · Story 3.2：只在关注行真正新建时发 UserFollowedEvent =====

    @Test
    void newFollowPublishesUserFollowedEventOnce() {
        targetExists();
        when(follows.insertIfAbsent(ME, TARGET)).thenReturn(1);
        Instant now = Instant.parse("2026-10-12T05:00:00Z");

        service(now).follow(ME, TOKEN);

        verify(events, Mockito.times(1)).publishEvent(new UserFollowedEvent(ME, TARGET, now));
    }

    /** 对方拉黑了我（D-4）：照常建行、照常发事件 —— FollowService 不判接收方隐藏关系（抑制在通知侧）。 */
    @Test
    void followWhenTargetBlockedMeStillPublishes() {
        targetExists();
        when(hideRelations.isBlocked(TARGET, ME)).thenReturn(true);
        when(hideRelations.isHidden(TARGET, ME)).thenReturn(true);
        when(follows.insertIfAbsent(ME, TARGET)).thenReturn(1);

        service(Instant.parse("2026-10-12T05:00:00Z")).follow(ME, TOKEN);

        verify(events).publishEvent(any(UserFollowedEvent.class));
        verify(hideRelations, never()).isHidden(anyLong(), anyLong());
    }

    @Test
    void idempotentAndConflictingFollowsDoNotPublish() {
        targetExists();
        when(follows.existsByFollowerIdAndFolloweeId(ME, TARGET)).thenReturn(true);
        service(Instant.parse("2026-10-12T05:00:00Z")).follow(ME, TOKEN);

        when(follows.existsByFollowerIdAndFolloweeId(ME, TARGET)).thenReturn(false);
        when(follows.insertIfAbsent(ME, TARGET)).thenReturn(0); // 并发双击的后一笔
        service(Instant.parse("2026-10-12T05:00:00Z")).follow(ME, TOKEN);

        verify(events, never()).publishEvent(any());
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    /** 3.2 code-review F5：每种拒绝都钉住状态码（串了也会红），且都不发事件。 */
    @Test
    void rejectedFollowsDoNotPublish() {
        targetExists();
        FollowService svc = service(Instant.parse("2026-10-12T05:00:00Z"));
        // 409：我拉黑了对方
        when(hideRelations.isBlocked(ME, TARGET)).thenReturn(true);
        assertRejected(() -> svc.follow(ME, TOKEN), HttpStatus.CONFLICT);
        when(hideRelations.isBlocked(ME, TARGET)).thenReturn(false);
        // 429：每日上限
        when(follows.countCreatedSince(eq(ME), any())).thenReturn(500L);
        assertRejected(() -> svc.follow(ME, TOKEN), HttpStatus.TOO_MANY_REQUESTS);
        // 404：对方不存在 / 注销 / 封号
        assertRejected(() -> svc.follow(ME, "z".repeat(32)), HttpStatus.NOT_FOUND);
        // 422：关注自己
        User me = Mockito.mock(User.class);
        when(me.getId()).thenReturn(ME);
        when(accounts.findActiveByPublicToken("m".repeat(32))).thenReturn(Optional.of(me));
        assertRejected(() -> svc.follow(ME, "m".repeat(32)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 401：调用方本人已失效（注销 / 封号后旧 access token）
        when(accounts.isActive(ME)).thenReturn(false);
        assertRejected(() -> svc.follow(ME, TOKEN), HttpStatus.UNAUTHORIZED);
        when(accounts.isActive(ME)).thenReturn(true);
        // 429：每分钟限流
        Mockito.doThrow(AppException.rateLimited("too fast")).when(rateLimiter)
                .check(eq("rl:social:follow:" + ME), eq(30), any());
        assertRejected(() -> svc.follow(ME, TOKEN), HttpStatus.TOO_MANY_REQUESTS);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void unfollowBlockUnfollowAndPurgeNeverPublish() {
        targetExists();
        when(follows.deleteRelation(ME, TARGET)).thenReturn(1);
        FollowService svc = service(Instant.parse("2026-10-12T05:00:00Z"));

        svc.unfollow(ME, TOKEN);
        svc.removeOnBlock(ME, TARGET);
        svc.purgeUser(ME);

        verify(events, never()).publishEvent(any());
    }
}
