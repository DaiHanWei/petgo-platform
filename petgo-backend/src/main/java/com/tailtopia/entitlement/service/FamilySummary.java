package com.tailtopia.entitlement.service;

import com.tailtopia.entitlement.domain.CreditFamily;
import java.time.Instant;
import java.util.List;

/** 按大类的汇总；{@code types} 只在 KTP 大类下按款（含 ANY）列出，其他大类为空列表。 */
public record FamilySummary(CreditFamily family, int remaining, Instant earliestExpiry, List<TypeSummary> types) { }
