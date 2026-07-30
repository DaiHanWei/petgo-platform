package com.tailtopia.triage.domain;

/**
 * 分诊详建付费解锁渠道（Story 2.2）。落库 varchar UPPER_SNAKE。仅 {@link UnlockSource#PAID} 时有值，
 * {@link UnlockSource#FREE_QUOTA}/{@link UnlockSource#LOCKED} 时恒 NULL。实际扣费/建单在 Story 2.3。
 */
public enum UnlockChannel {
    QRIS,
    PAWCOIN
}
