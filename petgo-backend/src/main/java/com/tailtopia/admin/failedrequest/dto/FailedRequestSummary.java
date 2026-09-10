package com.tailtopia.admin.failedrequest.dto;

/**
 * B21 未成功请求摘要条三格（V1.3.0 Story 9.2 · AC1）：本页签条数 · SYSTEM_FAILURE 数 · 已跟进数。
 *
 * @param total         当前页签（活动 / 已归档）的条数 —— 摘要**随页签联动**，
 *                      不是恒等于活动区（在「已归档」页签上给出活动区的数，读起来是骗人的）
 * @param systemFailure 其中 {@code SYSTEM_FAILURE} 的条数。🔴 这一格是本页存在的理由：
 *                      掉单里只有系统故障那部分是我们自己的问题，且**未跟进不可归档**
 * @param followedUp    其中已标记跟进的条数
 */
public record FailedRequestSummary(long total, long systemFailure, long followedUp) {
}
