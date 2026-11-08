package com.tailtopia.shop.review.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** V1.4.2 shop-admin Story 3.3：隐藏原因四选一，空 / 未知不默认成 OTHER。 */
class ReviewHideReasonTest {

    @Test
    void fourReasonsMatchMigrationCheck() {
        assertThat(ReviewHideReason.values())
                .containsExactly(ReviewHideReason.ABUSE, ReviewHideReason.AD, ReviewHideReason.PRIVACY, ReviewHideReason.OTHER);
    }

    @Test
    void parseIsLenientOnCaseAndBlank() {
        assertThat(ReviewHideReason.parse(" privacy ")).contains(ReviewHideReason.PRIVACY);
        assertThat(ReviewHideReason.parse("AD")).contains(ReviewHideReason.AD);
    }

    @Test
    void blankOrUnknownIsEmpty() {
        assertThat(ReviewHideReason.parse(null)).isEmpty();
        assertThat(ReviewHideReason.parse("  ")).isEmpty();
        assertThat(ReviewHideReason.parse("SPAM")).isEmpty();
    }
}
