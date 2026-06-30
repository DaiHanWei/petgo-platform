package com.tailtopia.admin.failedrequest.domain;

/**
 * 问诊请求失败原因（Story 2.9，AB-2G）。落库 varchar UPPER_SNAKE。
 * {@link #SYSTEM_FAILURE} 须强制跟进（未跟进不可归档）。
 */
public enum CancelReason {
    USER_CANCEL,
    TIMEOUT,
    SYSTEM_FAILURE;

    public static CancelReason fromOrDefault(String v) {
        if (v == null) {
            return USER_CANCEL;
        }
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            return USER_CANCEL;
        }
    }
}
