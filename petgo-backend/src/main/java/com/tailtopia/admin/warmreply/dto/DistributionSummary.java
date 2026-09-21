package com.tailtopia.admin.warmreply.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 帖子评论分布摘要条（V1.3.0 Story 4.1 AC4，一条聚合 SQL 随筛选联动）。
 *
 * @param posts        筛选范围内帖子数
 * @param avgAll       篇均可见评论数（含虚拟）；无帖时 null
 * @param avgReal      篇均可见评论数（剔除虚拟：评论作者 {@code account_type <> 'VIRTUAL'}）；无帖时 null
 * @param zeroPosts    0 评论帖数
 * @param virtualShare 虚拟评论占比 = 虚拟作者可见评论数 / 全部可见评论数（0～1）；无评论时 null
 */
public record DistributionSummary(long posts, BigDecimal avgAll, BigDecimal avgReal, long zeroPosts, BigDecimal virtualShare) {

    public static final DistributionSummary EMPTY = new DistributionSummary(0, null, null, 0, null);

    /** 0 评论帖占比（0～100，一位小数）；无帖时 null。 */
    public BigDecimal zeroPercent() {
        return posts == 0 ? null : BigDecimal.valueOf(zeroPosts).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(posts), 1, RoundingMode.HALF_UP);
    }

    /** 虚拟评论占比（0～100，一位小数）；无评论时 null。 */
    public BigDecimal virtualPercent() {
        return virtualShare == null ? null : virtualShare.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
    }

    public BigDecimal avgAllRounded() {
        return avgAll == null ? null : avgAll.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal avgRealRounded() {
        return avgReal == null ? null : avgReal.setScale(2, RoundingMode.HALF_UP);
    }
}
