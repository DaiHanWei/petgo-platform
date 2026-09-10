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

    /**
     * 排序键归一（V1.3.0 Story 9.1b）：同时接受 {@code avg_desc} 与 {@code avgDesc} 两种拼法。
     *
     * <p>🔴 这不是「兼容心太软」：退役的评分总览页发出去的链接用的是 snake_case（运营存了书签），
     * 而 Story 9.1b 的 AC3 把参数写成 camelCase。两种拼法都得认 ——
     * 认错的代价是**静默落进 default（均分倒序）**：下拉框仍高亮着运营选的那一项，
     * 界面上一点异常都看不出，只能靠人肉核对数字。
     *
     * <p>⚠️ 未知值仍然落默认（保守退化，与改前一致），但**已知的六种拼法必须精确命中**。
     */
    static String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        return switch (sort.trim()) {
            case AVG_DESC, "avgDesc" -> AVG_DESC;
            case AVG_ASC, "avgAsc" -> AVG_ASC;
            case VOLUME_DESC, "volumeDesc" -> VOLUME_DESC;
            default -> sort.trim();
        };
    }

    private Comparator<VetRatingOverviewRow> comparator(String rawSort) {
        String sort = normalizeSort(rawSort);
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
