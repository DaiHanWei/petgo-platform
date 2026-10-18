package com.tailtopia.profile.web;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.dto.PetListItemResponse;
import com.tailtopia.profile.service.PetListService;
import com.tailtopia.profile.service.PetResolver;
import com.tailtopia.tailsonality.service.TailsonalityBadgeQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** L0（V1.4.0 multi-pet AC4）：列表映射——status 恒 ACTIVE、只有默认那只 isDefault、diaryCount 按宠物计。 */
class PetListItemResponseTest {

    private static final long OWNER = 1L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final PetResolver resolver = mock(PetResolver.class);
    private final ContentService content = mock(ContentService.class);
    private final TailsonalityBadgeQuery badges = mock(TailsonalityBadgeQuery.class);
    private final PetListService service = new PetListService(resolver, content, badges);

    @Test
    void mapsStatusDefaultAndCounts() {
        when(resolver.listOwned(OWNER)).thenReturn(List.of(pet(OWNER, 10L, T0), pet(OWNER, 11L, T0.plusSeconds(1))));
        when(badges.badgeOf(anyLong())).thenReturn(Optional.empty());
        when(badges.badgeOf(11L)).thenReturn(Optional.of("ENTJ"));
        when(content.countGrowthMoments(OWNER, 10L)).thenReturn(3L);
        when(content.countGrowthMoments(OWNER, 11L)).thenReturn(0L);

        List<PetListItemResponse> list = service.listMine(OWNER);

        assertThat(list).extracting(PetListItemResponse::id).containsExactly(10L, 11L);
        assertThat(list).extracting(PetListItemResponse::status).containsOnly(PetListItemResponse.STATUS_ACTIVE);
        assertThat(list).filteredOn(PetListItemResponse::isDefault).extracting(PetListItemResponse::id)
                .containsExactly(10L);
        assertThat(list.get(0).diaryCount()).isEqualTo(3L);
        assertThat(list.get(1).tailsonalityBadge()).isEqualTo("ENTJ");
        assertThat(list.get(0).tailsonalityBadge()).isNull();
    }

    @Test
    void noPetsGivesEmptyList() {
        when(resolver.listOwned(OWNER)).thenReturn(List.of());
        assertThat(service.listMine(OWNER)).isEmpty();
    }
}
