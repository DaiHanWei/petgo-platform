package com.tailtopia.passport.dto;

import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceStamp;
import com.tailtopia.place.domain.PlaceType;
import java.time.LocalDate;

/**
 * 护照里的一枚章（V1.3.2 Story 1.2 · AC3.2）。Jackson NON_NULL。
 *
 * @param stampImageUrl 场所专属章 URL —— Story 1.2 / 1.3 恒为 null（Story 1.4 接入），null 时省略该键，
 *                      客户端按 {@code placeType} 用默认章
 * @param addressText   文字地址（Story 1.3）—— 仅 {@code placeStatus == ACTIVE} 时下发，否则省略
 */
public record PassportStampView(
        String placeToken,
        String placeName,
        PlaceType placeType,
        PlaceAvailability placeStatus,
        String stampImageUrl,
        LocalDate firstVisitDate,
        long visitCount,
        String addressText) {

    public static PassportStampView of(PlaceStamp s) {
        return new PassportStampView(s.placeToken(), s.placeName(), s.placeType(), s.availability(),
                null, s.firstVisitDate(), s.visitCount(),
                s.availability() == PlaceAvailability.ACTIVE ? s.addressText() : null);
    }
}
