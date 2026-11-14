package com.tailtopia.entitlement.service;

import com.tailtopia.entitlement.domain.CreditSource;
import java.util.List;

/**
 * 扣次数结果。{@code alreadyUsed = true}：该目标已扣过，本次未扣（{@code consumed = 0}、{@code creditIds} 为空、
 * {@code firstSource} 为 null）。
 */
public record ConsumeResult(boolean alreadyUsed, int consumed, List<Long> creditIds, CreditSource firstSource) { }
