package com.tailtopia.healthreminder.domain;

import java.util.Locale;

/** 提醒来源（落库 UPPER_SNAKE，埋点小写）。 */
public enum ReminderOrigin {
    RECORD,
    PROFILE,
    EVENT,
    LAUNCH_QUEUE,
    RESUME_CATCH_UP;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 整宠重建对账的范围：排队行与恢复补发行不随重建变化。 */
    public boolean rebuildManaged() {
        return this == RECORD || this == PROFILE || this == EVENT;
    }
}
