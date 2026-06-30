package com.tailtopia.admin.rating.service;

import com.tailtopia.admin.rating.dto.VetRatingOverviewRow;
import com.tailtopia.consult.dto.VetQualitySummary;
import com.tailtopia.consult.service.ConsultQualityQueryService;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.service.VetAccountService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 兽医评分总览聚合（Story 6.1，AB-6A）。**纯只读**：兽医清单经 {@link VetAccountService}、评分质量经
 * {@link ConsultQualityQueryService}（禁直访 consult/vet repo）。零评分兽医也出现（补 0）。无写、无审计。
 */
@Service
public class AdminRatingService {

    /** 排序键。默认 {@link #AVG_DESC}（均分由高到低）。 */
    public static final String AVG_DESC = "avg_desc";
    public static final String AVG_ASC = "avg_asc";
    public static final String VOLUME_DESC = "volume_desc";

    private final VetAccountService vetAccountService;
    private final ConsultQualityQueryService qualityService;

    public AdminRatingService(VetAccountService vetAccountService,
            ConsultQualityQueryService qualityService) {
        this.vetAccountService = vetAccountService;
        this.qualityService = qualityService;
    }

    /** 全兽医评分总览（可选时间窗 + 排序）。零评分兽医补 0 出现（AC1）。 */
    public List<VetRatingOverviewRow> overview(String sort, Instant from, Instant to) {
        List<VetRatingOverviewRow> rows = vetAccountService.listAll().stream()
                .map(v -> toRow(v, from, to))
                .sorted(comparator(sort))
                .toList();
        return rows;
    }

    private VetRatingOverviewRow toRow(VetAccount v, Instant from, Instant to) {
        VetQualitySummary q = qualityService.qualitySummary(v.getId(), from, to);
        return new VetRatingOverviewRow(v.getId(), v.getDisplayName(), q.average(),
                q.ratedCount(), q.unratedCount(), q.ratedCount() + q.unratedCount());
    }

    private Comparator<VetRatingOverviewRow> comparator(String sort) {
        return switch (sort == null ? AVG_DESC : sort) {
            case AVG_ASC -> Comparator.comparingDouble(VetRatingOverviewRow::average)
                    .thenComparing(VetRatingOverviewRow::vetId);
            case VOLUME_DESC -> Comparator.comparingInt(VetRatingOverviewRow::totalVolume).reversed()
                    .thenComparing(VetRatingOverviewRow::vetId);
            default -> Comparator.comparingDouble(VetRatingOverviewRow::average).reversed()
                    .thenComparing(VetRatingOverviewRow::vetId);
        };
    }
}
