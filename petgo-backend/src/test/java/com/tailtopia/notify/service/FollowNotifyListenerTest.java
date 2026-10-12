package com.tailtopia.notify.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.social.event.UserFollowedEvent;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** L0：新粉丝通知（V1.3.4 follow · Story 3.2 · AC2）—— 发送参数、隐藏抑制、24 小时去重、自关注、官方号。 */
@ExtendWith(MockitoExtension.class)
class FollowNotifyListenerTest {

    private static final long A = 2L;
    private static final long B = 9L;
    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");

    @Mock NotificationService notificationService;
    @Mock UserHideRelationReader hideRelations;
    @Mock NotificationRepository notifications;
    @Mock AccountQueryService accounts;

    private FollowNotifyListener listener() {
        return new FollowNotifyListener(notificationService, hideRelations, notifications, accounts,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UserFollowedEvent aFollowsB() {
        return new UserFollowedEvent(A, B, NOW);
    }

    private void verifyNotSent() {
        verify(notificationService, never()).sendInteraction(anyLong(), any(), anyLong(), any(), any(), any(),
                any(), any());
    }

    @Test
    void followSendsUserFollowedWithFollowerIdAsTargetRef() {
        listener().onUserFollowed(aFollowsB());

        verify(notifications).existsFollowedNotificationSince(B, A, Instant.parse("2026-10-11T05:00:00Z"));
        verify(notificationService).sendInteraction(B, NotificationType.USER_FOLLOWED, A, null, "2", null, null,
                null);
    }

    /** D-4：B 拉黑（或举报隐藏）了 A，A 关注 B → B 收不到通知。 */
    @Test
    void recipientHidingFollowerSuppresses() {
        when(hideRelations.isHidden(B, A)).thenReturn(true);

        listener().onUserFollowed(aFollowsB());

        verifyNotSent();
    }

    @Test
    void existingNotificationWithin24HoursSuppresses() {
        when(notifications.existsFollowedNotificationSince(B, A, Instant.parse("2026-10-11T05:00:00Z")))
                .thenReturn(true);

        listener().onUserFollowed(aFollowsB());

        verifyNotSent();
    }

    @Test
    void selfFollowIsIgnored() {
        listener().onUserFollowed(new UserFollowedEvent(A, A, NOW));

        verifyNotSent();
        org.mockito.Mockito.verifyNoInteractions(hideRelations, notifications, accounts);
    }

    /** D-9：官方内容账号不接收通知推送。 */
    @Test
    void officialAccountRecipientIsSkipped() {
        when(accounts.isOfficialAccount(B)).thenReturn(true);

        listener().onUserFollowed(aFollowsB());

        verifyNotSent();
    }
}
