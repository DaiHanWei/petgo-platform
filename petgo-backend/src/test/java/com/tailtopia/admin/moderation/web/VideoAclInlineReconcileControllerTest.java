package com.tailtopia.admin.moderation.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.moderation.service.AdminContentManageService;
import com.tailtopia.admin.moderation.service.ManualReviewService;
import com.tailtopia.admin.moderation.service.ManualReviewWorkbenchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.content.service.MediaAclReconciler;
import com.tailtopia.support.TestMessages;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** L0（专题线 video Story 3.1）：后台下架 / 恢复 / 审核处置先改状态、再事务外即时对账文件公开状态。 */
class VideoAclInlineReconcileControllerTest {

    private final MediaAclReconciler mediaAcl = mock(MediaAclReconciler.class);

    private static AdminUserDetails admin() {
        return new AdminUserDetails(7L, 99L, "ops@petgo", "{bcrypt}x", AdminAccountType.SUPER_ADMIN);
    }

    @Test
    void takedownAndRestoreReconcileAfterStateChange() {
        AdminContentManageService manage = mock(AdminContentManageService.class);
        AdminContentManageController c = new AdminContentManageController(manage, TestMessages.real(),
                mock(com.tailtopia.admin.throttle.service.AdminThrottleReadService.class),
                mock(com.tailtopia.admin.moderation.service.AdminContentDetailService.class), mediaAcl);

        c.takedown(admin(), 31L, "spam", HxRequest.NONE, new ConcurrentModel(), null, new RedirectAttributesModelMap());
        c.restore(admin(), 31L, HxRequest.NONE, new ConcurrentModel(), null, new RedirectAttributesModelMap());

        var order = inOrder(manage, mediaAcl);
        order.verify(manage).takedown(eq(31L), eq("spam"), anyLong());
        order.verify(mediaAcl).reconcileQuietly(31L);
        order.verify(manage).restore(eq(31L), anyLong());
        order.verify(mediaAcl).reconcileQuietly(31L);
    }

    @Test
    void manualReviewDecisionsReconcileReturnedPostId() {
        ManualReviewService review = mock(ManualReviewService.class);
        when(review.approve(eq(41L), anyLong())).thenReturn(901L);
        when(review.reject(eq(42L), anyLong(), any())).thenReturn(902L);
        ManualReviewAdminController c = new ManualReviewAdminController(review,
                mock(com.tailtopia.admin.moderation.service.AdminSettingsService.class),
                mock(com.tailtopia.admin.moderation.service.UnifiedTicketQueryService.class),
                mock(ManualReviewWorkbenchService.class), TestMessages.real(), mediaAcl);

        c.approve(admin(), 41L, HxRequest.NONE, new ConcurrentModel(), null, new RedirectAttributesModelMap());
        c.reject(admin(), 42L, "SPAM", "n", HxRequest.NONE, new ConcurrentModel(), null,
                new RedirectAttributesModelMap());

        var order = inOrder(review, mediaAcl);
        order.verify(review).approve(eq(41L), anyLong());
        order.verify(mediaAcl).reconcileQuietly(901L);
        order.verify(review).reject(eq(42L), anyLong(), any());
        order.verify(mediaAcl).reconcileQuietly(902L);
    }
}
