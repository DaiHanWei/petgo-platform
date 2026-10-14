package com.tailtopia.notify.ops.domain;

/**
 * 立即发送的活动已落库（V1.3.4 Story 6.2 · AC2.1）。新建事务提交后由
 * {@code OpsNotificationDispatcher} 以 {@code AFTER_COMMIT} + {@code @Async("opsNotificationExecutor")} 接收；
 * 事件丢失（进程重启）由定时扫描在 60 秒内兜底。
 */
public record OpsCampaignReadyEvent(long campaignId) {
}
