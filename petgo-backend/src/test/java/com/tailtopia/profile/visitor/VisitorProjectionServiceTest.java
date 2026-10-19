package com.tailtopia.profile.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.ArchiveStatsResponse;
import com.tailtopia.profile.dto.MilestoneGroupResponse;
import com.tailtopia.profile.dto.MilestoneItemResponse;
import com.tailtopia.profile.dto.MilestoneListResponse;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.profile.service.TimelineService;
import com.tailtopia.profile.support.PetFixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V1.4.0 multi-pet Story 1.3 · AC3 · L0：访客统计 / 徽章墙按这只宠物取（不再按账号默认宠物）。
 */
class VisitorProjectionServiceTest {

    private static final long OWNER = 7L;
    private static final long PET_B = 11L;

    private final TimelineService timelineService = mock(TimelineService.class);
    private final MilestoneService milestoneService = mock(MilestoneService.class);
    private final VisitorProjectionService service = new VisitorProjectionService(
            mock(ProfileService.class), mock(AccountQueryService.class), mock(ContentService.class),
            milestoneService, timelineService);

    private final PetProfile petB = PetFixtures.pet(OWNER, PET_B, PetType.DOG, Instant.parse("2026-02-01T00:00:00Z"));

    @Test
    void statsUseThatPet() {
        when(timelineService.getStats(OWNER, PET_B)).thenReturn(new ArchiveStatsResponse(3, 1, 2, 31, 9, 0, 0));

        VisitorStats s = service.stats(petB);

        assertThat(s.happyMomentCount()).isEqualTo(3);
        assertThat(s.consultCount()).isEqualTo(1);
        assertThat(s.milestoneCompleted()).isEqualTo(2);
        verify(timelineService).getStats(OWNER, PET_B);
        verify(timelineService, never()).getStats(anyLong());
    }

    @Test
    void completedMilestonesUseThatPet() {
        MilestoneItemResponse done = new MilestoneItemResponse("D-S1", "t", "S", "SYSTEM_AUTO", true,
                Instant.parse("2026-03-01T00:00:00Z"), null);
        MilestoneItemResponse todo = new MilestoneItemResponse("D-S2", "t", "S", "SYSTEM_AUTO", false, null, null);
        when(milestoneService.getMilestones(OWNER, PET_B)).thenReturn(new MilestoneListResponse("B", null, 1, 31,
                List.of(new MilestoneGroupResponse("S", 1, 2, List.of(done, todo)))));

        assertThat(service.completedMilestones(petB, 1)).containsExactly(done);
        verify(milestoneService, never()).getMilestones(anyLong());
    }

    /** 写库防护保持：没有已完成里程碑时不查清单。 */
    @Test
    void completedMilestonesSkipLookupWhenNoneCompleted() {
        assertThat(service.completedMilestones(petB, 0)).isEmpty();
        verify(milestoneService, never()).getMilestones(anyLong(), any());
    }
}
