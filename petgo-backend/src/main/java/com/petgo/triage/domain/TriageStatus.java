package com.petgo.triage.domain;

/**
 * 分诊任务状态（Story 4.1）。统一异步 DB 状态机：PENDING -> PROCESSING -> DONE/FAILED + retry_count。
 * 落库 varchar + UPPER_SNAKE。
 */
public enum TriageStatus {
    /** 已受理，待 @Async 处理。 */
    PENDING,
    /** 处理中（调 Gemini）。 */
    PROCESSING,
    /** 完成，结果就绪。 */
    DONE,
    /** 重试 >3 后降级，供前端降级 UI。 */
    FAILED
}
