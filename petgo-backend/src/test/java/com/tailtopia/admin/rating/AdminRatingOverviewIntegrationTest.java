package com.tailtopia.admin.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.rating.dto.VetRatingOverviewRow;
import com.tailtopia.admin.rating.service.AdminRatingService;
import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import com.tailtopia.vet.domain.VetAccount;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：兽医评分总览（Story 6.1，需 Docker postgres+redis）。造 RATED（有评分行）+ UNRATED（CLOSED 无评分）
 * 各一，验某兽医行 已评=1 / 未评=1 / 均分=4.0 / 总量=2 经真 PG 聚合。
 */
class AdminRatingOverviewIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminRatingService ratingService;
    @Autowired
    private VetTestSupport vetSupport;
    @Autowired
    private ConsultRatingRepository ratings;

    @Test
    void aggregatesRatedAndUnratedForVet() {
        VetAccount vet = vetSupport.newActiveVet("评分总览兽医-" + SEQ.incrementAndGet());
        long vetId = vet.getId();
        long u1 = 9_900_000L + SEQ.incrementAndGet();
        long u2 = 9_900_000L + SEQ.incrementAndGet();

        ConsultSession rated = vetSupport.newClosedSession(u1, vetId);
        vetSupport.newClosedSession(u2, vetId); // CLOSED 但无评分行 → 未评
        ratings.save(ConsultRating.of(rated.getId(), vetId, u1, 4, "不错"));

        List<VetRatingOverviewRow> rows = ratingService.overview(null, null, null);
        VetRatingOverviewRow row = rows.stream()
                .filter(r -> r.vetId() == vetId).findFirst().orElseThrow();

        assertThat(row.ratedCount()).isEqualTo(1);
        assertThat(row.unratedCount()).isEqualTo(1);
        assertThat(row.average()).isEqualTo(4.0);
        assertThat(row.totalVolume()).isEqualTo(2);
    }
}
