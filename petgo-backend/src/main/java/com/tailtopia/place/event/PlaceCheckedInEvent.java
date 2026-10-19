package com.tailtopia.place.event;

import com.tailtopia.place.domain.PlaceType;
import java.util.List;

/**
 * 打卡成功（V1.3.2 Story 1.2 · AC7.2）。埋点在 AFTER_COMMIT 消费（回滚的打卡不上报）。
 *
 * <p>🛡 不带坐标、宠物名、护照号；场所用 token 不用 id（AD-19）。
 *
 * <p>{@code petIds}：本次打卡选中的宠物（已校验归属、去重、保持请求顺序），供里程碑逐宠达成
 * （V1.3.4 Story 4.2）；<b>不进埋点</b>。
 *
 * <p>{@code checkinId}（V1.4.0 ① Story 1.1 追加）：本次打卡行 id，成长值 {@code PLACE_CHECKIN} 按它幂等；<b>不进埋点</b>。
 */
public record PlaceCheckedInEvent(long userId, String placeToken, PlaceType placeType,
        boolean isNewStamp, int stampCount, List<Long> petIds, long checkinId) {
}
