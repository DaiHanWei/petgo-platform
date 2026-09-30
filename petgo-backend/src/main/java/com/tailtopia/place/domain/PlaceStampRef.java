package com.tailtopia.place.domain;

/**
 * 章 + 内部场所 id（V1.3.2 Story 3.4）：只供护照快照冻结 / 现算章集合 hash 用，<b>不对外下发</b>（对外标识一律 token）。
 *
 * @param placeId       当前 {@code place_id}（聚合口径与 {@link PlaceStamp} 同一条 SQL）
 * @param lastVisitDate 最近到访（WIB 日）= max(visit_date)（Story 3.5 登机牌 DATE / 列表排序）
 */
public record PlaceStampRef(long placeId, PlaceStamp stamp, java.time.LocalDate lastVisitDate) {
}
