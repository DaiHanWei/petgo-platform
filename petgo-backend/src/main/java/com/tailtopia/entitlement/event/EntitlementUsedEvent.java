package com.tailtopia.entitlement.event;

import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditUseKind;

/**
 * 免费次数被使用（V1.5.0 gift-pack Story 4-3 · D-29）：解锁 / 送礼事务内发布，提交后上报 {@code entitlement_used}。
 * {@code alreadyUsed}（重放）时不发。只带枚举，无 PII。
 *
 * @param source 本次扣到的第一批的来源
 */
public record EntitlementUsedEvent(long userId, CreditUseKind kind, CreditSource source) {
}
