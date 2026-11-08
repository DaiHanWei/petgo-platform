package com.tailtopia.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L0：后台通用分页条的页码计算（V1.4.2 优化项：总页数 + 10 页一批 + 跳页）。 */
class AdminPagerTest {

    @Test
    void totalsAndCurrentBatch() {
        AdminPager p = AdminPager.of(0, 20, 14_400);
        assertThat(p.totalPages()).isEqualTo(720);
        assertThat(p.total()).isEqualTo(14_400);
        assertThat(p.batch()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(p.hasPrev()).isFalse();
        assertThat(p.hasNext()).isTrue();
        assertThat(p.hasPrevBatch()).isFalse();
        assertThat(p.hasNextBatch()).isTrue();
        assertThat(p.nextBatchPage()).isEqualTo(10);
    }

    @Test
    void middleBatchJumpsToAdjacentPages() {
        AdminPager p = AdminPager.of(13, 20, 14_400); // 第 14 页 → 第 11~20 页这一批
        assertThat(p.batch()).containsExactly(10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
        assertThat(p.prevBatchPage()).isEqualTo(9);   // « → 第 10 页
        assertThat(p.nextBatchPage()).isEqualTo(20);  // » → 第 21 页
    }

    @Test
    void lastBatchIsShortAndHasNoNext() {
        AdminPager p = AdminPager.of(24, 20, 495); // 25 页，最后一批 21~25
        assertThat(p.totalPages()).isEqualTo(25);
        assertThat(p.batch()).containsExactly(20, 21, 22, 23, 24);
        assertThat(p.hasNext()).isFalse();
        assertThat(p.hasNextBatch()).isFalse();
    }

    @Test
    void outOfRangePageIsClampedAndEmptyTableIsOnePage() {
        assertThat(AdminPager.of(99, 20, 45).page()).isEqualTo(2);
        assertThat(AdminPager.of(-3, 20, 45).page()).isZero();
        AdminPager empty = AdminPager.of(0, 20, 0);
        assertThat(empty.totalPages()).isEqualTo(1);
        assertThat(empty.visible()).isFalse();
    }

    @Test
    void unknownTotalOnlyAllowsStepping() {
        AdminPager p = AdminPager.unknown(3, true);
        assertThat(p.known()).isFalse();
        assertThat(p.batch()).isEmpty();
        assertThat(p.hasPrevBatch()).isFalse();
        assertThat(p.hasNextBatch()).isFalse();
        assertThat(p.hasPrev()).isTrue();
        assertThat(p.visible()).isTrue();
        assertThat(AdminPager.unknown(0, false).visible()).isFalse();
    }
}
