package com.tailtopia.passport.dto;

import com.tailtopia.place.domain.PlaceType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 已购版本回看（V1.3.2 Story 3.4 · AC7.2）：冻结的章 + 当前护照号 / 宠物名（这两项不属于「版本」，取实时值）。
 *
 * <p>🔴 <b>不下发内部 placeId</b>。章面图按<b>当前</b>专属章 key 现算（换章对历史生效）；null 省略 → 客户端按类型默认章。
 * 章的键名与 {@link PassportStampView} 同名（App 复用同一个章组件）。
 */
public record PassportSnapshotDetailResponse(String snapshotToken, Instant paidAt, int stampCount, String petName,
        String passportNo, List<Stamp> stamps) {

    public record Stamp(String placeToken, String placeName, PlaceType placeType, String stampImageUrl,
            LocalDate firstVisitDate, long visitCount) {
    }
}
