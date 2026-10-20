package com.tailtopia.growth.service;

/** 等级表已保存、重算任务已入库（Story 4.1）：事务提交后由 {@link GrowthRecalcService} 异步执行。 */
public record GrowthRecalcRequestedEvent(long jobId) {
}
