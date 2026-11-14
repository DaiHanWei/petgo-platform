package com.tailtopia.healthreminder.domain;

import java.util.Locale;

/** 推送点（落库 UPPER_SNAKE，埋点 {@link #wire()} 小写蛇形）。 */
public enum PushPoint {
    D_MINUS_7,
    D_MINUS_1,
    D_DAY,
    D_PLUS_3,
    D_PLUS_7,
    RESUME_CATCH_UP,
    NONE;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
