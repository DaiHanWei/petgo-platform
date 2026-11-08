package com.tailtopia.notify.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.social.event.PetFollowedEvent;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * L0：被追更通知（V1.4.2 social-topic · Story 1.2 · AC5）—— 自追更 / 任一方向拉黑 / 主人非 active / 24h 同宠物去重
 * 不发；24h 外、不同宠物照发；正常路径调 {@code sendPetFollowed(owner, follower, petId)}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PetFollowNotifyListenerTest {

    private static final long FOLLOWER = 2L;
    private static final long OWNER = 9L;
    private static final long PET = 70L;
    private static final Instant NOW = Instant.parse("2026-11-04T05:00:00Z");
    private static final Instant SINCE = Instant.parse("2026-11-03T05:00:00Z");

    @Mock NotificationService notificationService;
    @Mock UserHideRelationReader hideRelations;
    @Mock NotificationRepository notifications;
    @Mock AccountQueryService accounts;

    private PetFollowNotifyListener listener() {
        when(accounts.isActive(OWNER)).thenReturn(true);
        return new PetFollowNotifyListener(notificationService, hideRelations, notifications, accounts,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PetFollowedEvent event(long follower, long pet, long owner) {
        return new PetFollowedEvent(follower, pet, owner, NOW);
    }

    private void verifyNotSent() {
        verify(notificationService, never()).sendPetFollowed(anyLong(), anyLong(), anyLong());
    }

    @Test
    void followSendsPetFollowedToOwnerWithFollowerAndPet() {
        listener().onPetFollowed(event(FOLLOWER, PET, OWNER));

        verify(notifications).existsPetFollowedNotificationSince(OWNER, FOLLOWER, "70", SINCE);
        verify(notificationService).sendPetFollowed(OWNER, FOLLOWER, PET);
    }

    @Test
    void selfFollowIsNeverNotified() {
        listener().onPetFollowed(event(OWNER, PET, OWNER));
        verifyNotSent();
        verifyNoInteractions(notifications);
    }

    /** PRD AC9：主人拉黑（或举报隐藏）了追更者 → 主人收不到。 */
    @Test
    void ownerHidingFollowerSuppresses() {
        PetFollowNotifyListener l = listener();
        when(hideRelations.isHidden(OWNER, FOLLOWER)).thenReturn(true);
        l.onPetFollowed(event(FOLLOWER, PET, OWNER));
        verifyNotSent();
    }

    /** 追更者拉黑了主人（接口已 403，双保险）→ 不发。 */
    @Test
    void followerBlockingOwnerSuppresses() {
        PetFollowNotifyListener l = listener();
        when(hideRelations.isBlocked(FOLLOWER, OWNER)).thenReturn(true);
        l.onPetFollowed(event(FOLLOWER, PET, OWNER));
        verifyNotSent();
    }

    @Test
    void inactiveOwnerSuppresses() {
        PetFollowNotifyListener l = listener();
        when(accounts.isActive(OWNER)).thenReturn(false);
        l.onPetFollowed(event(FOLLOWER, PET, OWNER));
        verifyNotSent();
    }

    @Test
    void samePetWithin24hSuppresses() {
        PetFollowNotifyListener l = listener();
        when(notifications.existsPetFollowedNotificationSince(OWNER, FOLLOWER, "70", SINCE)).thenReturn(true);
        l.onPetFollowed(event(FOLLOWER, PET, OWNER));
        verifyNotSent();
    }

    /** 不同宠物分别通知：去重查询按宠物维度，另一只没有记录 → 照发。 */
    @Test
    void anotherPetIsNotifiedSeparately() {
        PetFollowNotifyListener l = listener();
        when(notifications.existsPetFollowedNotificationSince(OWNER, FOLLOWER, "70", SINCE)).thenReturn(true);
        l.onPetFollowed(event(FOLLOWER, 71L, OWNER));
        verify(notificationService).sendPetFollowed(OWNER, FOLLOWER, 71L);
    }

    /** 24h 之外：去重查询返回 false（窗口起点 = now − 24h）→ 照发。 */
    @Test
    void outside24hIsNotifiedAgain() {
        PetFollowNotifyListener l = listener();
        when(notifications.existsPetFollowedNotificationSince(OWNER, FOLLOWER, "70", SINCE)).thenReturn(false);
        l.onPetFollowed(event(FOLLOWER, PET, OWNER));
        verify(notificationService).sendPetFollowed(OWNER, FOLLOWER, PET);
    }
}
