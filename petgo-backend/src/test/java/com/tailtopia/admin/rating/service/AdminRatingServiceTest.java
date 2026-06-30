package com.tailtopia.admin.rating.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.rating.dto.VetRatingOverviewRow;
import com.tailtopia.consult.dto.VetQualitySummary;
import com.tailtopia.consult.service.ConsultQualityQueryService;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.service.VetAccountService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：兽医评分总览聚合（Story 6.1）——零评分兽医出现、排序、计数透传。 */
class AdminRatingServiceTest {

    private VetAccountService vetAccountService;
    private ConsultQualityQueryService qualityService;
    private AdminRatingService service;

    @BeforeEach
    void setUp() {
        vetAccountService = mock(VetAccountService.class);
        qualityService = mock(ConsultQualityQueryService.class);
        service = new AdminRatingService(vetAccountService, qualityService);
        // vetA：均分高、量大；vetB：零评分。先建 mock 再 thenReturn，避免嵌套 stubbing。
        VetAccount a = vet(1L, "A");
        VetAccount b = vet(2L, "B");
        when(vetAccountService.listAll()).thenReturn(List.of(a, b));
        when(qualityService.qualitySummary(eq(1L), any(), any()))
                .thenReturn(new VetQualitySummary(2, 0, 4.8));
        when(qualityService.qualitySummary(eq(2L), any(), any()))
                .thenReturn(new VetQualitySummary(0, 1, 0.0));
    }

    private VetAccount vet(long id, String name) {
        VetAccount v = mock(VetAccount.class);
        when(v.getId()).thenReturn(id);
        when(v.getDisplayName()).thenReturn(name);
        return v;
    }

    @Test
    void zeroRatingVetStillAppearsWithZeroes() {
        List<VetRatingOverviewRow> rows = service.overview(null, null, null);
        assertThat(rows).hasSize(2);
        VetRatingOverviewRow b = rows.stream().filter(r -> r.vetId() == 2L).findFirst().orElseThrow();
        assertThat(b.average()).isZero();
        assertThat(b.ratedCount()).isZero();
        assertThat(b.unratedCount()).isEqualTo(1);
        assertThat(b.totalVolume()).isEqualTo(1);
    }

    @Test
    void defaultSortIsAverageDesc() {
        List<VetRatingOverviewRow> rows = service.overview(null, null, null);
        assertThat(rows.get(0).vetId()).isEqualTo(1L); // 4.8 在前
    }

    @Test
    void avgAscPutsLowestFirst() {
        List<VetRatingOverviewRow> rows = service.overview(AdminRatingService.AVG_ASC, null, null);
        assertThat(rows.get(0).vetId()).isEqualTo(2L); // 0.0 在前
    }

    @Test
    void volumeDescPutsBusiestFirst() {
        List<VetRatingOverviewRow> rows = service.overview(AdminRatingService.VOLUME_DESC, null, null);
        assertThat(rows.get(0).vetId()).isEqualTo(1L); // volume 2 > 1
    }
}
