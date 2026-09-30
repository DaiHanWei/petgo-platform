package com.tailtopia.passport.dto;

import com.tailtopia.place.domain.PlaceAvailability;
import com.tailtopia.place.domain.PlaceStamp;
import com.tailtopia.place.domain.PlaceType;
import java.time.LocalDate;

/**
 * 护照里的一枚章（V1.3.2 Story 1.2 · AC3.2）。Jackson NON_NULL。
 *
 * @param stampImageUrl 场所专属章 URL —— <b>本 story 恒为 null</b>（Story 1.4 接入），null 时省略该键，
 *                      客户端按 {@code placeType} 用默认章
 */
public record PassportStampView(
        String placeToken,
        String placeName,
        PlaceType placeType,
        PlaceAvailability placeStatus,
        String stampImageUrl,
        LocalDate firstVisitDate,
        long visitCount) {

    public static PassportStampView of(PlaceStamp s) {
        return new PassportStampView(s.placeToken(), s.placeName(), s.placeType(), s.availability(),
                null, s.firstVisitDate(), s.visitCount());
    }
}
