package com.tailtopia.shop.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.review.domain.ReviewStatus;
import com.tailtopia.shop.review.domain.ShopReview;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（v1.4.2 shop Story 6.1 AC3.3）：评价隐藏的实体语义。 */
class ShopReviewHiddenTest {

    private static ShopReview published() {
        ShopReview r = ShopReview.of(1L, 2L, 3L, 4L, 4, "ok", List.of());
        ReflectionTestUtils.setField(r, "reviewStatus", ReviewStatus.PUBLISHED);
        return r;
    }

    @Test
    void hideIsIdempotentAndKeepsTheFirstInstant() {
        ShopReview r = published();
        assertThat(r.isHidden()).isFalse();
        Instant t1 = Instant.parse("2026-11-05T01:00:00Z");
        r.hide(t1, com.tailtopia.shop.review.domain.ReviewHideReason.OTHER, null);
        assertThat(r.isHidden()).isTrue();
        assertThat(r.getHiddenAt()).isEqualTo(t1);
        r.hide(t1.plusSeconds(3600), com.tailtopia.shop.review.domain.ReviewHideReason.OTHER, null);
        assertThat(r.getHiddenAt()).as("已隐藏再隐藏不改写时刻").isEqualTo(t1);
        assertThat(r.isPublished()).as("隐藏不改审核状态").isTrue();
        assertThat(r.getReviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);
    }

    @Test
    void unhideRestoresAndIsIdempotent() {
        ShopReview r = published();
        r.unhide();
        assertThat(r.isHidden()).isFalse();
        r.hide(Instant.now(), com.tailtopia.shop.review.domain.ReviewHideReason.OTHER, null);
        r.unhide();
        assertThat(r.isHidden()).isFalse();
        assertThat(r.getHiddenAt()).isNull();
        assertThat(r.isPublished()).isTrue();
    }

    @Test
    void toStringNeverPrintsContent() {
        ShopReview r = published();
        r.hide(Instant.now(), com.tailtopia.shop.review.domain.ReviewHideReason.OTHER, null);
        assertThat(r.toString()).doesNotContain("ok");
    }
}
