package com.tailtopia.entitlement.service;

import java.util.List;

/** 可用权益汇总（只统计未用完且未过期的批）。 */
public record CreditSummary(int totalRemaining, List<FamilySummary> families) { }
