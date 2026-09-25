package com.tailtopia.profile.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.profile.service.TimelineService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：访客时间线的私密条目口径（2026-09-25 code review #2）。
 *
 * <p>分享链接（H5，cardToken 不可枚举）= 主人授权 → 含私密（PRD §2.9 定稿）；
 * 站内访客入口按自增 petId 寻址、主人未分享 → 私密条目整条不下发。
 */
class VisitorPrivateTimelineTest {

    private final ContentService content = mock(ContentService.class);
    private final VisitorProjectionService service = new VisitorProjectionService(
            mock(ProfileService.class), mock(AccountQueryService.class), content,
            mock(MilestoneService.class), mock(TimelineService.class));

    private static GrowthMomentView moment(long id, ContentVisibility v) {
        return new GrowthMomentView(id, Instant.parse("2026-09-24T02:00:00Z"), LocalDate.of(2026, 9, 24),
                List.of("https://cdn/p" + id + ".jpg"), "text-" + id, v, PostStatus.PUBLISHED);
    }

    private static PetProfile pet() {
        PetProfile p = PetProfile.create(7L, PetType.CAT, "Miu", null, "Ragdoll", LocalDate.of(2024, 6, 1), null, "tok");
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", 42L);
        return p;
    }

    @Test
    @DisplayName("🔴 站内入口（includePrivate=false）：私密条目整条不下发，正文与图片都不出现")
    void inAppDropsPrivateEntries() {
        when(content.findRecentGrowthMomentsByEventDate(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                moment(1, ContentVisibility.PUBLIC), moment(2, ContentVisibility.PRIVATE)));

        List<VisitorTimelineItem> items = service.timeline(pet(), 30, false);

        assertThat(items).extracting(VisitorTimelineItem::postId).containsExactly(1L);
        assertThat(items).extracting(VisitorTimelineItem::text).doesNotContain("text-2");
    }

    @Test
    @DisplayName("分享链接入口（默认 / includePrivate=true）口径不变：含私密条目（PRD §2.9 定稿）")
    void shareLinkKeepsPrivateEntries() {
        when(content.findRecentGrowthMomentsByEventDate(anyLong(), anyLong(), anyInt())).thenReturn(List.of(
                moment(1, ContentVisibility.PUBLIC), moment(2, ContentVisibility.PRIVATE)));

        assertThat(service.timeline(pet(), 30)).extracting(VisitorTimelineItem::postId).containsExactly(1L, 2L);
    }
}
