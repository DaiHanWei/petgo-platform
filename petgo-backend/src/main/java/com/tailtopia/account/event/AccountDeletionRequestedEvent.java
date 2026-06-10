package com.tailtopia.account.event;

/** 注销已受理事件（Story 7.3）。@Async @TransactionalEventListener(AFTER_COMMIT) 消费触发级联作业。 */
public record AccountDeletionRequestedEvent(long deletionId) {
}
