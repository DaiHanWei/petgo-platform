package com.tailtopia.admin.risk.dto;

import java.time.Instant;

/**
 * B14 抽屉里的一条 RED 分诊记录（V1.3.0 Story 8.5 · AC4）。
 *
 * <p>🔴 **刻意只带任务 id / 状态 / 时间，不带 symptomText、不带解析结果、不带图**：
 * 那些是**健康数据**（架构 §Enforcement：日志与后台展示都不外泄健康数据）。
 * 本页的用途是「这个用户红了几次、什么时候红的」，判断要不要人工介入 ——
 * 回答这个问题不需要看症状描述，而一旦把它摆出来，后台就多了一个健康数据泄漏面。
 */
public record RedTaskRow(long taskId, String status, Instant createdAt) {
}
