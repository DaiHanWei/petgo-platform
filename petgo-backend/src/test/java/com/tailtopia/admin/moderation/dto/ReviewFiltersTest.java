package com.tailtopia.admin.moderation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * L0：统一复核工作台的页签 / 筛选解析（V1.3.0 Story 2.4 AC1、AC5、AC8）。
 * 旧链接 {@code ?type=} 兼容、{@code HX-Current-URL} 还原筛选、页签按 sub_type 归类、页签可扩展。
 */
class ReviewFiltersTest {

    @Test
    void tabParamAndLegacyType() {
        assertThat(ReviewTab.fromParam(null)).isEqualTo(ReviewTab.SUBMISSION);
        assertThat(ReviewTab.fromParam("bogus")).isEqualTo(ReviewTab.SUBMISSION);
        assertThat(ReviewTab.fromParam("Avatar")).isEqualTo(ReviewTab.AVATAR);
        assertThat(ReviewTab.fromParam("NAME")).isEqualTo(ReviewTab.NAME);

        // AC1：旧 ?type= 链接映射到页签；ACCOUNT_IDENTITY 落名称页签；A2 的用户举报与未知值不映射。
        assertThat(ReviewTab.fromLegacyType("CONTENT_REPORT")).isEqualTo(ReviewTab.REPORT);
        assertThat(ReviewTab.fromLegacyType("CONTENT_SUBMISSION")).isEqualTo(ReviewTab.SUBMISSION);
        assertThat(ReviewTab.fromLegacyType("ACCOUNT_IDENTITY")).isEqualTo(ReviewTab.NAME);
        assertThat(ReviewTab.fromLegacyType("ACCOUNT_REPORT")).isNull();
        assertThat(ReviewTab.fromLegacyType("nope")).isNull();
        assertThat(ReviewTab.fromLegacyType(" ")).isNull();
    }

    @Test
    void ofPrefersTabOverLegacyTypeAndMapsStatusToState() {
        ReviewFilters f = ReviewFilters.of("report", "CONTENT_SUBMISSION", null, null, null, null, null, " x ", 2);
        assertThat(f.tab()).isEqualTo(ReviewTab.REPORT);
        assertThat(f.handled()).isFalse();
        assertThat(f.q()).isEqualTo("x");
        assertThat(f.page()).isEqualTo(2);

        // 旧 ?status=HANDLED → 已处理态；?status=PENDING → 待处理；显式 state 优先。
        assertThat(ReviewFilters.of(null, "CONTENT_REPORT", null, "HANDLED", null, null, null, null, null).handled())
                .isTrue();
        assertThat(ReviewFilters.of(null, null, null, "PENDING", null, null, null, null, null).handled()).isFalse();
        assertThat(ReviewFilters.of(null, null, "pending", "HANDLED", null, null, null, null, null).handled()).isFalse();
        assertThat(ReviewFilters.of(null, null, "handled", null, null, null, null, null, -3).page()).isZero();
        assertThat(ReviewFilters.of(null, "ACCOUNT_REPORT", null, null, null, null, null, null, null).tab())
                .isEqualTo(ReviewTab.SUBMISSION);
    }

    @Test
    void fromUrlRestoresFiltersAndFallsBackToDefault() {
        ReviewFilters f = ReviewFilters.fromUrl(
                "https://api-stag.tailtopia.id/admin/manual-review?tab=name&state=handled&subType=pet&q=%E7%8B%97&page=3");
        assertThat(f.tab()).isEqualTo(ReviewTab.NAME);
        assertThat(f.handled()).isTrue();
        assertThat(f.subType()).isEqualTo("pet");
        assertThat(f.q()).isEqualTo("狗");
        assertThat(f.page()).isEqualTo(3);
        assertThat(f.state()).isEqualTo("handled");

        assertThat(ReviewFilters.fromUrl(null)).isEqualTo(ReviewFilters.DEFAULT);
        assertThat(ReviewFilters.fromUrl("/admin/manual-review")).isEqualTo(ReviewFilters.DEFAULT);
        assertThat(ReviewFilters.fromUrl("::not a url::")).isEqualTo(ReviewFilters.DEFAULT);
        assertThat(ReviewFilters.fromUrl("/admin/manual-review?page=abc").page()).isZero();

        // 处置后回到该页签待处理第一页（AC5「下一条」口径）。
        ReviewFilters next = f.withTab(ReviewTab.AVATAR);
        assertThat(next.tab()).isEqualTo(ReviewTab.AVATAR);
        assertThat(next.page()).isZero();
        assertThat(next.subType()).isEqualTo("pet");
    }

    @Test
    void rowFallsIntoTabBySubType() {
        assertThat(ReviewTab.of(row(TicketType.ACCOUNT_IDENTITY, "PET_AVATAR"))).isEqualTo(ReviewTab.AVATAR);
        assertThat(ReviewTab.of(row(TicketType.ACCOUNT_IDENTITY, "USER_AVATAR"))).isEqualTo(ReviewTab.AVATAR);
        assertThat(ReviewTab.of(row(TicketType.ACCOUNT_IDENTITY, "NICKNAME"))).isEqualTo(ReviewTab.NAME);
        assertThat(ReviewTab.of(row(TicketType.CONTENT_REPORT, null))).isEqualTo(ReviewTab.REPORT);
        assertThat(ReviewTab.of(row(TicketType.CONTENT_SUBMISSION, "P1 · CONTENT_POST"))).isEqualTo(ReviewTab.SUBMISSION);
    }

    /** AC8：页签结构由枚举驱动——每个页签都有 i18n key、类型与 sub_type 集合，加一行即出现新页签。 */
    @Test
    void tabsAreSelfDescribing() {
        for (ReviewTab t : ReviewTab.values()) {
            assertThat(t.titleKey()).isEqualTo("admin.v130.review.tab." + t.param());
            assertThat(t.type()).isNotNull();
            assertThat(t.subTypes()).isNotNull();
        }
        assertThat(ReviewTab.NAME.hasSubTypeFilter()).isTrue();
        assertThat(ReviewTab.REPORT.hasSubTypeFilter()).isFalse();
        assertThat(new ReviewDisposeResult(ReviewTab.REPORT, "9", 7L, java.util.Map.of(), 0).rowId())
                .isEqualTo("review-row-report-7");
    }

    private static UnifiedTicketRow row(TicketType type, String subType) {
        return new UnifiedTicketRow(type, 1L, subType, 2L, "n", false, TicketStatusBucket.PENDING,
                1, 1, 0, 5, Instant.now(), "p", null, null, 0);
    }
}
