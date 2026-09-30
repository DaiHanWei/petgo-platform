package com.tailtopia.place.domain;

import java.time.LocalDate;

/**
 * 一枚「章」（V1.3.2 Story 1.2 · AD-5）：某宠物在某场所（按<b>当前</b> {@code place_id}）全部打卡的聚合。
 * <b>不建章表</b>：合并场所后打卡已由后台改挂保留方，章自动合为一枚、次数相加。
 *
 * @param placeToken     场所 token（不外露自增 id）
 * @param placeName      场所名
 * @param placeType      类型；库里是客户端未知的值时为 null（章面回落通用占位）
 * @param availability   ACTIVE / UNAVAILABLE（下架 / 软删 / 异常 MERGED）
 * @param firstVisitDate 首次到访（WIB 日）= min(visit_date)
 * @param visitCount     到访次数 = count
 */
public record PlaceStamp(String placeToken, String placeName, PlaceType placeType,
        PlaceAvailability availability, LocalDate firstVisitDate, long visitCount) {
}
