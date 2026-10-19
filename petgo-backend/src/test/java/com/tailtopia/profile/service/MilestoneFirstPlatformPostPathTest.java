package com.tailtopia.profile.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.profile.domain.MilestoneAutoEvent;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * L0：S5「首条平台帖子」的判定口径（2026-08-05 修）——按**对外可见**判，不按内容类型判。
 *
 * <p>回归的缺陷：V1.1.2 把 Diary（{@code GROWTH_MOMENT}）设成有宠用户的默认发布类型，
 * 「同步到 Moment」开关只改 visibility 不改 type（Story 4.1/4.2）。原实现只认 {@code DAILY}，
 * 于是顺着默认路径发帖、内容已进广场，S5 却永不解锁（连带卡死新手任务第 5 件）。
 */
class MilestoneFirstPlatformPostPathTest {

    private static final Instant NOW = Instant.parse("2026-08-05T07:00:00Z");

    private final MilestoneCompletionService completion = mock(MilestoneCompletionService.class);
    private final com.tailtopia.content.service.ContentService contentService =
            mock(com.tailtopia.content.service.ContentService.class);
    private final MilestoneAutoCompleteListener listener = new MilestoneAutoCompleteListener(completion, contentService);

    {
        // V1.4.0 multi-pet（Story 1.3）：计数按这只宠物（9L）的已发布 Diary 数判。
        org.mockito.Mockito.when(contentService.countPublishedGrowthMomentsOfPet(7L, 9L)).thenReturn(1L);
    }

    private static ContentPublishedEvent event(ContentType type, ContentVisibility visibility) {
        long growthCount = type == ContentType.GROWTH_MOMENT ? 1L : 0L;
        return new ContentPublishedEvent(100L, 7L, type, 9L, growthCount, visibility, NOW);
    }

    /** 本次修的主场景：Diary 开同步（GROWTH_MOMENT + PUBLIC）→ 既走计数类，也解锁 S5。 */
    @Test
    void publicDiaryEntryCompletesS5AndStillCountsGrowthMoment() {
        listener.onContentPublished(event(ContentType.GROWTH_MOMENT, ContentVisibility.PUBLIC));

        // V1.4.0 multi-pet（Story 1.3 · P-7）：Diary 的 S5 归其关联宠物。
        verify(completion).completeForPet(9L, MilestoneAutoEvent.PLATFORM_POST,
                MilestoneCompletionSource.SYSTEM_AUTO);
        // 原有 S2 / M10 / L5 计数路径不受影响（不得为了修 S5 把计数判定挤掉）。
        verify(completion).onGrowthMomentCountForPet(9L, 1L);
        verify(completion).completeDateGatedLNodesForPet(9L);
    }

    /** 私密 Diary 只进作者自己的档案，不进任何公开位 → 不算平台发帖，但计数类照旧。 */
    @Test
    void privateDiaryEntryDoesNotCompleteS5ButStillCounts() {
        listener.onContentPublished(event(ContentType.GROWTH_MOMENT, ContentVisibility.PRIVATE));

        verify(completion, never()).completeForPet(
                anyLong(), eq(MilestoneAutoEvent.PLATFORM_POST), any());
        verify(completion, never()).completeForDefaultPet(
                anyLong(), eq(MilestoneAutoEvent.PLATFORM_POST), any());
        verify(completion).onGrowthMomentCountForPet(9L, 1L);
    }

    /** Moment（DAILY）恒算平台发帖 —— 修改前的唯一路径，必须保持。 */
    @Test
    void momentPostCompletesS5() {
        listener.onContentPublished(event(ContentType.DAILY, ContentVisibility.PUBLIC));

        // P-7：Moment 归默认宠物。
        verify(completion).completeForDefaultPet(7L, MilestoneAutoEvent.PLATFORM_POST,
                MilestoneCompletionSource.SYSTEM_AUTO);
        // 非成长时刻不进计数类判定。
        verify(completion, never()).onGrowthMomentCountForPet(anyLong(), anyLong());
    }

    /** 公开科普帖同样是「平台上的一条帖子」→ 算 S5，且不碰成长日历计数。 */
    @Test
    void publicKnowledgePostCompletesS5WithoutGrowthCount() {
        listener.onContentPublished(event(ContentType.KNOWLEDGE, ContentVisibility.PUBLIC));

        // P-7：Tips 归默认宠物。
        verify(completion).completeForDefaultPet(7L, MilestoneAutoEvent.PLATFORM_POST,
                MilestoneCompletionSource.SYSTEM_AUTO);
        verify(completion, never()).onGrowthMomentCountForPet(anyLong(), anyLong());
    }
}
