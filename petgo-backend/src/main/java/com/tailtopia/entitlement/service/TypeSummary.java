package com.tailtopia.entitlement.service;

import com.tailtopia.entitlement.domain.CreditType;
import java.time.Instant;

/** 按类型的汇总（剩余 0 的类型不列）。 */
public record TypeSummary(CreditType type, int remaining, Instant earliestExpiry) { }
