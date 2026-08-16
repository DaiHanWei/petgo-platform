package com.tailtopia.notify.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentRemovedEvent;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0：内容互动推送 —— 被赞/被评推给作者；自互动不推；逐条独立 send。
 *
 * <p>V1.1.4 Story 1.4 追加：互动通知抑制的两条判据（含 R3 第三方那一支）与「系统通知不受影响」。
 */
@ExtendWith(MockitoExtension.class)
class ContentNotifyListenerTest {

    @Mock
    NotificationService notificationService;

    /** Story 1.4：默认无任何隐藏关系（isHidden → false），既有 8 例语义一字不变。 */
    @Mock
    UserHideRelationReader hideRelations;

    private ContentNotifyListener listener() {
        return new ContentNotifyListener(notificationService, hideRelations);
    }

    @Test
    void likedNotifiesAuthor() {
        listener().onContentLiked(new ContentLikedEvent(55L, 2L, 9L, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_LIKED),
                anyString(), anyString(), eq(NotificationType.CONTENT_LIKED.name()), eq("55"));
    }

    @Test
    void selfLikeNotPushed() {
        listener().onContentLiked(new ContentLikedEvent(55L, 9L, 9L, Instant.now()));
        verify(notificationService, never()).send(anyLongArg(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void commentNotifiesContentAuthor() {
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 2L, 9L, null, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_COMMENTED),
                anyString(), anyString(), eq(NotificationType.CONTENT_COMMENTED.name()), eq("55"));
    }

    @Test
    void selfCommentNotPushed() {
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 9L, 9L, null, Instant.now()));
        verify(notificationService, never()).send(anyLongArg(), any(), anyString(), anyString(), anyString(), anyString());
    }

    // Bug 20260625-088：回复二级评论时，被回复人(parentAuthor)也应收到通知。
    @Test
    void replyNotifiesBothContentAuthorAndParentAuthor() {
        // post 作者 9；评论者 2 回复了 5 的一级评论。
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 2L, 9L, 5L, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_COMMENTED),
                anyString(), anyString(), eq(NotificationType.CONTENT_COMMENTED.name()), eq("55"));
        verify(notificationService).send(eq(5L), eq(NotificationType.CONTENT_COMMENTED),
                anyString(), anyString(), eq(NotificationType.CONTENT_COMMENTED.name()), eq("55"));
    }

    @Test
    void replyToOwnCommentDoesNotNotifySelf() {
        // 评论者 5 回复自己的一级评论；内容作者 9。仅通知作者 9，不给自己(5)发。
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 5L, 9L, 5L, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_COMMENTED),
                anyString(), anyString(), anyString(), eq("55"));
        verify(notificationService, never()).send(eq(5L), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void replyWhereParentIsContentAuthorNotifiedOnce() {
        // 被回复人 9 恰是内容作者：只推一条（去重，不双推）。
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 2L, 9L, 9L, Instant.now()));
        verify(notificationService, org.mockito.Mockito.times(1)).send(eq(9L), any(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void contentRemovedNotifiesAuthor() {
        listener().onContentRemoved(new ContentRemovedEvent(55L, 9L, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_REMOVED),
                anyString(), anyString(), eq(NotificationType.CONTENT_REMOVED.name()), eq("55"));
    }

    private static long anyLongArg() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private static NotificationType any() {
        return org.mockito.ArgumentMatchers.any();
    }

    // ===== V1.1.4 Story 1.4：互动通知抑制 =====

    /** AC1：我拉黑的人来点赞 → 一条通知都不发（不是发了再隐藏，是压根不调 send）。 */
    @Test
    void likeFromHiddenUserIsSuppressed() {
        when(hideRelations.isHidden(9L, 2L)).thenReturn(true); // 作者 9 隐藏了点赞者 2
        listener().onContentLiked(new ContentLikedEvent(55L, 2L, 9L, Instant.now()));
        verify(notificationService, never()).send(anyLongArg(), any(), anyString(), anyString(),
                anyString(), anyString());
    }

    /** AC1：我拉黑的人来评论我的内容 → 不发。 */
    @Test
    void commentFromHiddenUserIsSuppressedForContentAuthor() {
        when(hideRelations.isHidden(9L, 2L)).thenReturn(true);
        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 2L, 9L, null, Instant.now()));
        verify(notificationService, never()).send(anyLongArg(), any(), anyString(), anyString(),
                anyString(), anyString());
    }

    /**
     * ⚠️ AC3 / R3 —— 本 story 唯一一条 PRD 没覆盖的场景，也是最容易漏的一条。
     *
     * <p>A（内容作者 9）拉黑了 B（评论者 2）；B 在 A 的帖子下回复了 C（一级评论作者 5）的评论。
     * <b>C 没有拉黑任何人</b>，但那条回复因 R2 对所有人隐藏 —— 若只判「接收者是否隐藏了 actor」，
     * C 会收到一条<b>点进去什么都没有</b>的通知，一对比就能推断出屏蔽机制存在。
     *
     * <p>判据②的 holder 是 contentAuthorId（A），接收者却是 parentAuthorId（C）——<b>两个不同的人</b>。
     */
    @Test
    void replyToThirdPartyIsAlsoSuppressedWhenContentAuthorHidTheCommenter() {
        when(hideRelations.isHidden(9L, 2L)).thenReturn(true); // A(9) 隐藏了 B(2)
        // C(5) 什么都没做：isHidden(5, 2) 默认 false

        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 2L, 9L, 5L, Instant.now()));

        verify(notificationService, never()).send(eq(9L), any(), anyString(), anyString(),
                anyString(), anyString()); // A 收不到
        verify(notificationService, never()).send(eq(5L), any(), anyString(), anyString(),
                anyString(), anyString()); // ⚠️ C 也收不到
    }

    /** 反向：只有被回复者自己拉黑了 actor → 只压他这一条，内容作者照常收到。 */
    @Test
    void replySuppressionIsPerRecipientWhenOnlyParentAuthorHid() {
        when(hideRelations.isHidden(9L, 2L)).thenReturn(false); // A(9) 没拉黑谁
        when(hideRelations.isHidden(5L, 2L)).thenReturn(true);  // C(5) 隐藏了 B(2)

        listener().onContentCommented(new ContentCommentedEvent(55L, 7L, 2L, 9L, 5L, Instant.now()));

        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_COMMENTED),
                anyString(), anyString(), anyString(), eq("55")); // 内容作者照常收到
        verify(notificationService, never()).send(eq(5L), any(), anyString(), anyString(),
                anyString(), anyString());
    }

    /**
     * AC4：系统通知一律不接抑制。
     *
     * <p>这里断言的是<b>它连隐藏关系都不查</b> —— 比「查了但照样发」更强：
     * 只要有人日后顺手把抑制加进 {@code onContentRemoved}，这条会立刻红。
     */
    @Test
    void contentRemovedNeverConsultsHideRelations() {
        listener().onContentRemoved(new ContentRemovedEvent(55L, 9L, Instant.now()));
        verify(notificationService).send(eq(9L), eq(NotificationType.CONTENT_REMOVED),
                anyString(), anyString(), eq(NotificationType.CONTENT_REMOVED.name()), eq("55"));
        org.mockito.Mockito.verifyNoInteractions(hideRelations);
    }

    /** 接收者恰是内容作者时，两条判据同源 → 只查一次库（AD-18：同步查库，不加缓存，别白查）。 */
    @Test
    void likeChecksHideRelationOnlyOnceWhenRecipientIsTheAuthor() {
        listener().onContentLiked(new ContentLikedEvent(55L, 2L, 9L, Instant.now()));
        verify(hideRelations, org.mockito.Mockito.times(1)).isHidden(9L, 2L);
        org.mockito.Mockito.verifyNoMoreInteractions(hideRelations);
    }
}
