package com.tailtopia.place.dto;

import java.time.Instant;

/**
 * 时间线用的打卡投影（V1.3.2 Story 1.6）。
 *
 * @param checkinId   打卡 id —— <b>只在后端内部</b>用于与关联帖去重，不外露
 * @param checkedAt   打卡时刻（UTC，排序键与有效日期都按它）
 * @param placeToken  当前场所 token（合并后是保留方）
 * @param placeName   场所名
 * @param placeStatus ACTIVE / UNAVAILABLE（{@code PlaceAvailability}）
 */
public record PlaceCheckinTimelineView(long checkinId, Instant checkedAt, String placeToken, String placeName,
        String placeStatus) {
}
