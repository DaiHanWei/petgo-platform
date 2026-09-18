package com.tailtopia.admin.warmreply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.warmreply.dto.DistributionFilter;
import com.tailtopia.admin.warmreply.dto.DistributionSummary;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** L0：分布页签筛选条件规范化（V1.3.0 Story 4.1 AC2）与摘要条百分比换算（AC4）。 */
class DistributionFilterTest {

    @Test
    void defaultsAreLast7DaysAllStatusExcludeVirtual() {
        DistributionFilter f = DistributionFilter.of(null, null, null, null, null, null, null, null);
        LocalDate today = LocalDate.now(ScheduleWindow.WIB);
        assertThat(f.count()).isEqualTo("all");
        assertThat(f.maxCount()).isNull();
        assertThat(f.to()).isEqualTo(today);
        assertThat(f.from()).isEqualTo(today.minusDays(6));
        assertThat(f.species()).isNull();
        assertThat(f.status()).isEqualTo("all");
        assertThat(f.excludeVirtual()).isTrue();
        assertThat(f.page()).isZero();
        assertThat(f.offset()).isZero();
    }

    @Test
    void countShortcutsAndCustomN() {
        assertThat(DistributionFilter.of("zero", null, null, null, null, null, false, 2).maxCount()).isZero();
        assertThat(DistributionFilter.of("le3", null, null, null, null, null, false, 2).maxCount()).isEqualTo(3);
        DistributionFilter custom = DistributionFilter.of("custom", 5, null, null, null, null, false, 2);
        assertThat(custom.maxCount()).isEqualTo(5);
        assertThat(custom.excludeVirtual()).isFalse();
        assertThat(custom.offset()).isEqualTo(40);
        // 非 custom 时 n 被忽略
        assertThat(DistributionFilter.of("le3", 99, null, null, null, null, null, null).n()).isNull();
        // 未知取值回默认
        assertThat(DistributionFilter.of("bogus", null, null, null, "FISH", "weird", null, -3))
                .satisfies(f -> {
                    assertThat(f.count()).isEqualTo("all");
                    assertThat(f.species()).isNull();
                    assertThat(f.status()).isEqualTo("all");
                    assertThat(f.page()).isZero();
                });
        assertThat(DistributionFilter.of(null, null, null, null, "CAT", "visible", null, null).species()).isEqualTo("CAT");
    }

    @Test
    void customWithoutPositiveNIs422() {
        for (Integer bad : new Integer[] {null, 0, -1}) {
            assertThatThrownBy(() -> DistributionFilter.of("custom", bad, null, null, null, null, null, null))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.comments.distribution.badCount"));
        }
    }

    @Test
    void controllerParsesNLeniently() {
        assertThat(com.tailtopia.admin.warmreply.web.AdminCommentDistributionController.parseN(null)).isNull();
        assertThat(com.tailtopia.admin.warmreply.web.AdminCommentDistributionController.parseN(" ")).isNull();
        assertThat(com.tailtopia.admin.warmreply.web.AdminCommentDistributionController.parseN(" 5 ")).isEqualTo(5);
        assertThat(com.tailtopia.admin.warmreply.web.AdminCommentDistributionController.parseN("abc")).isEqualTo(-1);
    }

    @Test
    void reversedRangeIsSwapped() {
        DistributionFilter f = DistributionFilter.of(null, null, LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 1), null, null, null, null);
        assertThat(f.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(f.to()).isEqualTo(LocalDate.of(2026, 9, 8));
    }

    @Test
    void summaryPercentages() {
        DistributionSummary s = new DistributionSummary(8, new BigDecimal("2.3333"), new BigDecimal("1.5"), 3, new BigDecimal("0.35714"));
        assertThat(s.zeroPercent()).isEqualByComparingTo("37.5");
        assertThat(s.virtualPercent()).isEqualByComparingTo("35.7");
        assertThat(s.avgAllRounded()).isEqualByComparingTo("2.33");
        assertThat(s.avgRealRounded()).isEqualByComparingTo("1.50");
        assertThat(DistributionSummary.EMPTY.zeroPercent()).isNull();
        assertThat(DistributionSummary.EMPTY.virtualPercent()).isNull();
    }
}
