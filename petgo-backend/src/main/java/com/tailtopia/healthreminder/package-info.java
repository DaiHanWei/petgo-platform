/**
 * V1.5.0 健康记录提醒引擎（架构增量 health-reminder §2 / §4 / §5）：上线回填、提醒表、重建、日跑、投递、点开。
 * 依赖 profile（读仓储 / 实体 / {@code profile.health} 规则）与 notify；profile 不依赖本包。
 * 🔒 健康数据 = PII：日志只记 id + 动作 + 计数。
 */
package com.tailtopia.healthreminder;
