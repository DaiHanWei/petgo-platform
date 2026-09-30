package com.tailtopia.passport.dto;

import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceType;
import java.time.LocalDate;

/**
 * 登机牌详情（V1.3.2 Story 3.5 · AC4）。不下发距离、不下发内部 id。
 *
 * @param placeToken    解析后的场所 token（MERGED → 保留方；App 以它为准）
 * @param addressText   仅 ACTIVE 时下发
 * @param city          仅 ACTIVE 时下发
 * @param placeImageUrl 该场所首张可见照片；无 → null（App 按类型默认图 / 占位）
 * @param seat          装饰座位号（{@code BoardingPassSeat}），不存储
 * @param unlockToken   解锁行 token；未建行 → null
 */
public record BoardingPassDetailResponse(String placeToken, String passenger, String breed, String placeName,
        String passportNo, LocalDate lastVisitDate, LocalDate firstVisitDate, long visitCount, String seat,
        PlaceType placeType, PlaceAvailability placeStatus, String placeImageUrl, String stampImageUrl,
        String addressText, String city, boolean unlocked, String unlockToken) {
}
