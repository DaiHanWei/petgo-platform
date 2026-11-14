package com.tailtopia.healthreminder.domain;

/** 提醒大类：同类同推送点合并（FR-166 Same-type merge）只在疫苗 / 驱虫 / Vet visit / Custom 内发生。 */
public enum ReminderFamily {
    VACCINE,
    DEWORM,
    VET_VISIT,
    CUSTOM,
    HEAT,
    NEUTER,
    SUGGESTION,
    CHECKUP;

    /** 可参与同类合并的大类。 */
    public boolean mergeable() {
        return this == VACCINE || this == DEWORM || this == VET_VISIT || this == CUSTOM;
    }
}
