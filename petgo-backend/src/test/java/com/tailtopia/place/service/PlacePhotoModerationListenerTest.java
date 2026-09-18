package com.tailtopia.place.service;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.moderation.DegradeReason;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.place.event.PlacePhotosSubmittedEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 补充照片异步审核的判定分支（batch-b1 复审 B1）。
 *
 * <p>🔴 只有**干净 PASS** 才放行：{@code approveModeration} 会同时开放 og:image 资格，
 * 而站外预览卡被社交平台缓存后撤不回来。RISKY 此前落进了放行分支。
 */
class PlacePhotoModerationListenerTest {

    private ContentModerationService moderation;
    private PlacePhotoService photos;
    private PlacePhotoModerationListener listener;

    private final PlacePhotosSubmittedEvent event =
            new PlacePhotosSubmittedEvent(List.of(1L, 2L), List.of("https://cdn/a.jpg", "https://cdn/b.jpg"));

    @BeforeEach
    void setUp() {
        moderation = Mockito.mock(ContentModerationService.class);
        photos = Mockito.mock(PlacePhotoService.class);
        listener = new PlacePhotoModerationListener(moderation, photos);
    }

    private void verdict(ModerationOutcome outcome) {
        when(moderation.evaluate(anyString(), anyList())).thenReturn(outcome);
    }

    @Test
    void cleanPassApprovesEveryPhoto() {
        verdict(ModerationOutcome.pass(0.1, null));

        listener.onPhotosSubmitted(event);

        verify(photos).approve(1L);
        verify(photos).approve(2L);
    }

    @Test
    void riskyStaysPendingAndIsNeverApproved() {
        verdict(ModerationOutcome.risky(0.7, "PORN"));

        listener.onPhotosSubmitted(event);

        verify(photos, never()).approve(anyLong());
        verify(photos, never()).reject(anyLong());
    }

    @Test
    void degradedStaysPending() {
        verdict(ModerationOutcome.degraded(DegradeReason.TIMEOUT));

        listener.onPhotosSubmitted(event);

        verify(photos, never()).approve(anyLong());
        verify(photos, never()).reject(anyLong());
    }

    @Test
    void imageBlockedRejectsTheBatch() {
        verdict(ModerationOutcome.imageBlocked("PORN"));

        listener.onPhotosSubmitted(event);

        verify(photos).reject(1L);
        verify(photos).reject(2L);
        verify(photos, never()).approve(anyLong());
    }
}
