package com.tailtopia.place.event;

import com.tailtopia.place.domain.PlaceType;

/**
 * 打卡成功（V1.3.2 Story 1.2 · AC7.2）。埋点在 AFTER_COMMIT 消费（回滚的打卡不上报）。
 *
 * <p>🛡 不带坐标、宠物名、护照号；场所用 token 不用 id（AD-19）。
 */
public record PlaceCheckedInEvent(long userId, String placeToken, PlaceType placeType,
        boolean isNewStamp, int stampCount) {
}
