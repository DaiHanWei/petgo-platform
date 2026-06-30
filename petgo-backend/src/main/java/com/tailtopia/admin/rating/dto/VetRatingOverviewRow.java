package com.tailtopia.admin.rating.dto;

/**
 * 兽医评分总览行（Story 6.1，AB-6A）。仅运营可见。
 *
 * @param average     均分（1-5，一位小数；无评分 0.0）
 * @param ratedCount  已评问诊数
 * @param unratedCount 未评问诊数（CLOSED 无评分）
 * @param totalVolume 总问诊量（已评 + 未评）
 */
public record VetRatingOverviewRow(long vetId, String displayName, double average,
        int ratedCount, int unratedCount, int totalVolume) {
}
