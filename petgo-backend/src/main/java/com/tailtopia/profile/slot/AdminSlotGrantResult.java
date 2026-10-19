package com.tailtopia.profile.slot;

import java.time.Instant;

/**
 * 运营补发结果（V1.4.0 multi-pet Story 4.3 · AC5.5）。
 *
 * @param rentalExpiresAt 补发名额的到期时刻（UTC）
 * @param replayed        同一 {@code grantToken} 已处理过（双击 / 回退重提）：未新建名额，调用方不再写审计
 * @param days            实际补发的天数：重放时为<b>首次</b>补发的天数（与本次请求填的天数无关，toast 必须用它）
 */
public record AdminSlotGrantResult(Instant rentalExpiresAt, boolean replayed, int days) {
}
