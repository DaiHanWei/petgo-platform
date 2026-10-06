package com.tailtopia.passport.dto;

import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceType;
import java.time.LocalDate;
import java.util.List;

/**
 * 登机牌列表（V1.3.2 Story 3.5 · AC3）：该宠物每个打过卡的（当前）场所一张，按最近到访倒序。
 * 价格不进本接口（App 读定价接口）。🔴 不下发内部 placeId。
 *
 * @param stampImageUrl 专属章公开 URL（列表章面缩略用；null 省略 → 客户端按类型默认章）
 */
public record BoardingPassListResponse(String petName, String passportNo, List<Item> items) {

    public record Item(String placeToken, String placeName, PlaceType placeType, PlaceAvailability placeStatus,
            String stampImageUrl, LocalDate lastVisitDate, long visitCount, boolean unlocked) {
    }
}
