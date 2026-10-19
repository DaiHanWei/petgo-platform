package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.ReverseReason;

/**
 * 一次扣回请求（V1.4.0 ① 架构 AD-5）。按原流水幂等键反查；原流水不存在（当初没入账）则不扣。
 *
 * @param originalIdemKey 原流水的幂等键
 * @param reason          扣回原因
 * @param sourceKey       来源键（拼进反向流水幂等键，见 {@link GrowthIdemKeys#reverse}）
 * @param amountOverride  非 null 时只扣这么多（部分退款，Story 1.3）；仍不超过原流水剩余可扣
 */
public record ReverseCommand(String originalIdemKey, ReverseReason reason, String sourceKey, Integer amountOverride) {
}
