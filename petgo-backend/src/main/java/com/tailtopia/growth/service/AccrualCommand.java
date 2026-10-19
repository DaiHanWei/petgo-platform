package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.RuleCode;
import java.time.Instant;
import java.util.List;

/**
 * 一次入账请求（V1.4.0 ① 架构 AD-5）。监听只负责拼它，参与判定 / 额度 / 幂等全在 {@link GrowthLedgerService}。
 *
 * @param userId         入账给谁
 * @param rule           规则代码
 * @param pointsOverride 非 null 时替代规则分值（按金额换算的规则先经 {@link GrowthLedgerService#pointsForAmount}）
 * @param idemKey        幂等键（只经 {@link GrowthIdemKeys} 生成）
 * @param refType        关联对象类型（可空）
 * @param refId          关联对象 id（可空）
 * @param petIds         关联宠物（可空）
 * @param occurredAt     行为发生时刻（决定 WIB 额度日）
 */
public record AccrualCommand(long userId, RuleCode rule, Integer pointsOverride, String idemKey, String refType,
        String refId, List<Long> petIds, Instant occurredAt) {
}
