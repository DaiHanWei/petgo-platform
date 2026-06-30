package com.tailtopia.consult.domain;

/**
 * 会话中断原因（Story 5.7 / 3.2）。
 * {@link #VET_BANNED} 运营封禁兽医导致；{@link #USER_DEACTIVATED} 运营停用用户导致进行中会话强制中断。
 */
public enum InterruptReason {
    VET_BANNED,
    USER_DEACTIVATED
}
