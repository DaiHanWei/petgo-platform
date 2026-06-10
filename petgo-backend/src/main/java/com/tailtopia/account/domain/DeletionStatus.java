package com.petgo.account.domain;

/** 注销作业状态机（Story 7.3）。DB 状态机驱动，可重试、可审计、不半途残留。 */
public enum DeletionStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED
}
