package com.tailtopia.admin.risk.dto;

/**
 * B14 红色超额摘要条（V1.3.0 Story 8.5 · AC4）：待核查用户数。
 *
 * <p>🔴 本页**纯观测**：这个数不触发任何自动处置，只是告诉运营「还有几个人没看过」。
 */
public record RedOverageSummary(long toVerify) {
}
