package com.tailtopia.profile.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.event.ConsultClosedEvent;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentCommentedEvent;
import com.tailtopia.content.event.ContentLikedEvent;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.MilestoneAutoEvent;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.event.CardSharedEvent;
import com.tailtopia.profile.event.HealthArchivedEvent;
import com.tailtopia.profile.event.HealthRecordCreatedEvent;
import com.tailtopia.profile.event.ProfileCreatedEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V1.4.0 multi-pet Story 1.3 · AC6 / P-7 · L0：里程碑自动完成按宠物路由。
 *
 * <p>逐行钉住 AC6 表：带宠物的事件只完成那只宠物；内容触发归帖子关联宠物（Diary → 其宠物，
 * Moment / Tips → 默认宠物）；问诊结束归单上宠物、无单 → 默认宠物；任何路径都<b>不再</b>走账号全部宠物。
 */
class MilestoneAutoCompleteListenerTest {

    private static final long OWNER = 7L;
    private static final long PET_A = 10L;
    private static final long PET_B = 11L;
    private static final Instant NOW = Instant.parse("2026-10-18T03:00:00Z");
    private static final MilestoneCompletionSource AUTO = MilestoneCompletionSource.SYSTEM_AUTO;

    private final MilestoneCompletionService completion = mock(MilestoneCompletionService.class);
    private final ContentService contentService = mock(ContentService.class);
    private final MilestoneAutoCompleteListener listener =
            new MilestoneAutoCompleteListener(completion, contentService);

    private void verifyNeverAccountWide() {
        verify(completion, never()).completeForOwner(anyLong(), any(), any());
        verify(completion, never()).maybeUnlockLulusPemulaForOwner(anyLong());
    }

    @Test
    void profileCreatedCompletesThatPet() {
        listener.onProfileCreated(new ProfileCreatedEvent(OWNER, PET_B, NOW));
        verify(completion).completeForPet(PET_B, MilestoneAutoEvent.PROFILE_CREATED, AUTO);
        verifyNeverAccountWide();
    }

    /** Diary 计数按这只宠物的已发布 Diary 数判（B 有 10 条 → 传 10，不是作者跨宠总数 25）。 */
    @Test
    void diaryPublishCountsThatPetsDiaries() {
        when(contentService.countPublishedGrowthMomentsOfPet(OWNER, PET_B)).thenReturn(10L);

        listener.onContentPublished(new ContentPublishedEvent(100L, OWNER, ContentType.GROWTH_MOMENT, PET_B,
                25L, ContentVisibility.PRIVATE, NOW));

        verify(completion).onGrowthMomentCountForPet(PET_B, 10L);
        verify(completion).completeDateGatedLNodesForPet(PET_B);
        verify(completion, never()).onGrowthMomentCountForPet(PET_A, 10L);
        verifyNeverAccountWide();
    }

    @Test
    void diaryWithoutPetDoesNotTriggerCounting() {
        listener.onContentPublished(new ContentPublishedEvent(100L, OWNER, ContentType.GROWTH_MOMENT, null,
                1L, ContentVisibility.PRIVATE, NOW));

        verify(completion, never()).onGrowthMomentCountForPet(anyLong(), anyLong());
        verify(completion, never()).completeDateGatedLNodesForPet(anyLong());
    }

    @Test
    void publicDiaryPlatformPostGoesToItsPet() {
        when(contentService.countPublishedGrowthMomentsOfPet(OWNER, PET_B)).thenReturn(1L);

        listener.onContentPublished(new ContentPublishedEvent(100L, OWNER, ContentType.GROWTH_MOMENT, PET_B,
                1L, ContentVisibility.PUBLIC, NOW));

        verify(completion).completeForPet(PET_B, MilestoneAutoEvent.PLATFORM_POST, AUTO);
        verify(completion, never()).completeForDefaultPet(anyLong(), any(), any());
        verifyNeverAccountWide();
    }

    @Test
    void momentPlatformPostGoesToDefaultPet() {
        listener.onContentPublished(new ContentPublishedEvent(100L, OWNER, ContentType.DAILY, null,
                0L, ContentVisibility.PUBLIC, NOW));

        verify(completion).completeForDefaultPet(OWNER, MilestoneAutoEvent.PLATFORM_POST, AUTO);
        verifyNeverAccountWide();
    }

    @Test
    void cardSharedCompletesThatPet() {
        listener.onCardShared(new CardSharedEvent(OWNER, PET_B, NOW));
        verify(completion).completeForPet(PET_B, MilestoneAutoEvent.CARD_SHARED, AUTO);
        verifyNeverAccountWide();
    }

    @Test
    void healthArchivedCompletesThatPet() {
        listener.onHealthArchived(new HealthArchivedEvent(OWNER, PET_B, NOW));
        verify(completion).completeForPet(PET_B, MilestoneAutoEvent.CONSULT_ARCHIVED, AUTO);
        verifyNeverAccountWide();
    }

    @Test
    void healthRecordCompletesThatPetAndJudgesLulusForThatPet() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(OWNER, HealthRecordType.VACCINE, PET_B));
        verify(completion).completeForPet(PET_B, MilestoneAutoEvent.HEALTH_RECORD_VACCINE, AUTO);
        verify(completion).maybeUnlockLulusPemulaForPet(PET_B);
        verifyNeverAccountWide();
    }

    @Test
    void consultClosedWithOrderPetCompletesThatPet() {
        listener.onConsultClosed(consultClosed(PET_B));
        verify(completion).completeForPetOrDefault(OWNER, PET_B, MilestoneAutoEvent.CONSULT_CLOSED, AUTO);
        verifyNeverAccountWide();
    }

    @Test
    void consultClosedWithoutOrderFallsBackToDefaultPet() {
        listener.onConsultClosed(consultClosed(null));
        verify(completion).completeForPetOrDefault(OWNER, null, MilestoneAutoEvent.CONSULT_CLOSED, AUTO);
        verifyNeverAccountWide();
    }

    @Test
    void likeOnDiaryGoesToItsPet() {
        when(contentService.growthMomentPetIdsOf(100L)).thenReturn(List.of(PET_B));
        listener.onContentLiked(new ContentLikedEvent(100L, 99L, OWNER, NOW));
        verify(completion).completeForPet(PET_B, MilestoneAutoEvent.FIRST_LIKE, AUTO);
        verifyNeverAccountWide();
    }

    @Test
    void likeOnMomentGoesToDefaultPet() {
        when(contentService.growthMomentPetIdsOf(100L)).thenReturn(List.of());
        listener.onContentLiked(new ContentLikedEvent(100L, 99L, OWNER, NOW));
        verify(completion).completeForDefaultPet(OWNER, MilestoneAutoEvent.FIRST_LIKE, AUTO);
        verifyNeverAccountWide();
    }

    @Test
    void commentOnDiaryGoesToItsPet() {
        when(contentService.growthMomentPetIdsOf(100L)).thenReturn(List.of(PET_A));
        listener.onContentCommented(new ContentCommentedEvent(100L, 1L, 99L, OWNER, null, NOW, null));
        verify(completion).completeForPet(PET_A, MilestoneAutoEvent.FIRST_COMMENT, AUTO);
        verifyNeverAccountWide();
    }

    @Test
    void commentOnTipsGoesToDefaultPetAndSelfCommentIgnored() {
        when(contentService.growthMomentPetIdsOf(100L)).thenReturn(List.of());
        listener.onContentCommented(new ContentCommentedEvent(100L, 1L, OWNER, OWNER, null, NOW, null));
        verify(completion, never()).completeForDefaultPet(anyLong(), any(), any());

        listener.onContentCommented(new ContentCommentedEvent(100L, 2L, 99L, OWNER, null, NOW, null));
        verify(completion).completeForDefaultPet(OWNER, MilestoneAutoEvent.FIRST_COMMENT, AUTO);
        verifyNeverAccountWide();
    }

    // ===== V1.4.0 multi-pet Story 3.1（AC7.1 / P-7）：Diary 按 petIds 逐只 =====

    /** petIds = [A, B] → 两只各自按自己的已发布 Diary 数完成计数类 / L 级回填；公开 → 两只各一次首条平台帖子。 */
    @Test
    void multiPetDiaryCompletesEachLinkedPet() {
        when(contentService.countPublishedGrowthMomentsOfPet(OWNER, PET_A)).thenReturn(1L);
        when(contentService.countPublishedGrowthMomentsOfPet(OWNER, PET_B)).thenReturn(10L);

        listener.onContentPublished(new ContentPublishedEvent(100L, OWNER, ContentType.GROWTH_MOMENT, PET_A,
                11L, ContentVisibility.PUBLIC, NOW, List.of(PET_A, PET_B)));

        verify(completion).onGrowthMomentCountForPet(PET_A, 1L);
        verify(completion).onGrowthMomentCountForPet(PET_B, 10L);
        verify(completion).completeDateGatedLNodesForPet(PET_A);
        verify(completion).completeDateGatedLNodesForPet(PET_B);
        verify(completion).completeForPet(PET_A, MilestoneAutoEvent.PLATFORM_POST, AUTO);
        verify(completion).completeForPet(PET_B, MilestoneAutoEvent.PLATFORM_POST, AUTO);
        verify(completion, never()).completeForDefaultPet(anyLong(), any(), any());
        verifyNeverAccountWide();
    }

    /** Moment 带的 petIds（理论上恒空）不触发 Diary 计数；首条平台帖子仍账号默认宠物一次。 */
    @Test
    void momentPlatformPostStillDefaultPetOnce() {
        listener.onContentPublished(new ContentPublishedEvent(100L, OWNER, ContentType.DAILY, null,
                0L, ContentVisibility.PUBLIC, NOW, List.of()));

        verify(completion, never()).onGrowthMomentCountForPet(anyLong(), anyLong());
        verify(completion, org.mockito.Mockito.times(1))
                .completeForDefaultPet(OWNER, MilestoneAutoEvent.PLATFORM_POST, AUTO);
        verifyNeverAccountWide();
    }

    /** 多宠 Diary 被赞 → 每只关联宠物各完成一次「第一次被赞」。 */
    @Test
    void likeOnMultiPetDiaryGoesToEachPet() {
        when(contentService.growthMomentPetIdsOf(100L)).thenReturn(List.of(PET_A, PET_B));
        listener.onContentLiked(new ContentLikedEvent(100L, 99L, OWNER, NOW));
        verify(completion).completeForPet(PET_A, MilestoneAutoEvent.FIRST_LIKE, AUTO);
        verify(completion).completeForPet(PET_B, MilestoneAutoEvent.FIRST_LIKE, AUTO);
        verify(completion, never()).completeForDefaultPet(anyLong(), any(), any());
        verifyNeverAccountWide();
    }

    private static ConsultClosedEvent consultClosed(Long petId) {
        return new ConsultClosedEvent(1L, OWNER, 42L, petId, "im-1", List.of(), true,
                LocalDate.of(2026, 10, 18), "摘要", "GREEN", "建议");
    }
}
