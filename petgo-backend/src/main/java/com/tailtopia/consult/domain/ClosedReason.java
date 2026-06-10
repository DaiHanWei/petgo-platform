package com.tailtopia.consult.domain;

/** 会话关闭原因（Story 5.6）。{@link #RATED} 用户评分关闭；{@link #UNRATED} 30min 超时未评自动关闭。 */
public enum ClosedReason {
    RATED,
    UNRATED
}
