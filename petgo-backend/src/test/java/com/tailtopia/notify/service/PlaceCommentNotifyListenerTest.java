package com.tailtopia.notify.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.place.event.PlaceCommentApprovedEvent;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** L0：场所评论通知（V1.3.4 follow · Story 3.2 · AC4）—— 参数、自评、注销 / 封号、官方号、隐藏。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaceCommentNotifyListenerTest {

    private static final long CREATOR = 9L;
    private static final long COMMENTER = 2L;
    private static final String TOKEN = "p".repeat(32);

    @Mock NotificationService notificationService;
    @Mock UserHideRelationReader hideRelations;
    @Mock AccountQueryService accounts;

    @BeforeEach
    void setUp() {
        when(accounts.isActive(CREATOR)).thenReturn(true);
    }

    private PlaceCommentNotifyListener listener() {
        return new PlaceCommentNotifyListener(notificationService, hideRelations, accounts);
    }

    private static PlaceCommentApprovedEvent event(long commenter) {
        return new PlaceCommentApprovedEvent(77L, TOKEN, CREATOR, 500L, commenter, Instant.now());
    }

    private void verifyNotSent() {
        verify(notificationService, never()).sendInteraction(anyLong(), any(), anyLong(), any(), any(), any(),
                any(), any());
    }

    @Test
    void approvedCommentNotifiesCreatorWithAggregateKeyAndPlaceToken() {
        listener().onPlaceCommentApproved(event(COMMENTER));

        // comment_id 留空：place_comments 的 id 空间与内容评论独立，填进去会被当成内容评论去查。
        verify(notificationService).sendInteraction(CREATOR, NotificationType.PLACE_COMMENTED, COMMENTER,
                "COMMENT:PLACE:77", TOKEN, null, null, null);
    }

    @Test
    void creatorCommentingOwnPlaceIsIgnored() {
        listener().onPlaceCommentApproved(event(CREATOR));
        verifyNotSent();
    }

    /** 注销与封号（D-8）都是 isActive == false。 */
    @Test
    void inactiveCreatorIsSkipped() {
        when(accounts.isActive(CREATOR)).thenReturn(false);
        listener().onPlaceCommentApproved(event(COMMENTER));
        verifyNotSent();
    }

    @Test
    void officialCreatorIsSkipped() {
        when(accounts.isOfficialAccount(CREATOR)).thenReturn(true);
        listener().onPlaceCommentApproved(event(COMMENTER));
        verifyNotSent();
    }

    @Test
    void creatorHidingCommenterSuppresses() {
        when(hideRelations.isHidden(CREATOR, COMMENTER)).thenReturn(true);
        listener().onPlaceCommentApproved(event(COMMENTER));
        verifyNotSent();
    }
}
