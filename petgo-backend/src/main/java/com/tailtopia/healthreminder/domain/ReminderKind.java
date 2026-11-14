package com.tailtopia.healthreminder.domain;

import com.tailtopia.notify.domain.NotificationType;

/**
 * 提醒目录（V1.5.0 Story 2.1，PRD 附录 C 原样，架构增量 §4.1）。<b>声明顺序 = 附录 C 行序 = 目录序</b>（同档同日比较的最后一级）。
 * 名字与附录 C 的 ID、迁移 {@code ck_health_reminders_kind}、地基 {@code NotificationType.HEALTH_*} 一一对应（守卫测试）。
 *
 * <p>档位：PYOMETRA_WATCH 1；VET_VISIT_D_MINUS_1 / VET_VISIT_D_DAY / CUSTOM_D_DAY 2；VACCINE_* 3；DEWORM_* 4；STITCH_CHECK 5；
 * HEAT_DOG_D_MINUS_7 6；CHECKUP 7；FIRST_VACCINE / FIRST_DEWORM / SPAY_NEUTER_SUGGESTION / HEAT_CAT_SECOND / HEAT_MALE_FIRST 8。
 * 不可移动：所有 D-Day、D-1 与 PYOMETRA_WATCH（永远发）；其余可移动（顺延 ≤3 天）。
 */
public enum ReminderKind {
    VACCINE_D_MINUS_7(3, true, ReminderRoute.PAGE, ReminderFamily.VACCINE),
    VACCINE_D_DAY(3, false, ReminderRoute.FORM, ReminderFamily.VACCINE),
    VACCINE_D_PLUS_7(3, true, ReminderRoute.FORM, ReminderFamily.VACCINE),
    FIRST_VACCINE(8, true, ReminderRoute.FORM_DATE_EMPTY, ReminderFamily.SUGGESTION),
    DEWORM_INTERNAL_D_DAY(4, false, ReminderRoute.FORM, ReminderFamily.DEWORM),
    DEWORM_EXTERNAL_D_DAY(4, false, ReminderRoute.FORM, ReminderFamily.DEWORM),
    DEWORM_FLEA_WORM_D_DAY(4, false, ReminderRoute.FORM, ReminderFamily.DEWORM),
    DEWORM_HEARTWORM_D_DAY(4, false, ReminderRoute.FORM, ReminderFamily.DEWORM),
    DEWORM_INTERNAL_D_PLUS_3(4, true, ReminderRoute.FORM, ReminderFamily.DEWORM),
    DEWORM_EXTERNAL_D_PLUS_3(4, true, ReminderRoute.FORM, ReminderFamily.DEWORM),
    DEWORM_LEGACY_D_DAY(4, false, ReminderRoute.FORM, ReminderFamily.DEWORM),
    DEWORM_LEGACY_D_PLUS_3(4, true, ReminderRoute.FORM, ReminderFamily.DEWORM),
    FIRST_DEWORM(8, true, ReminderRoute.FORM_DATE_EMPTY, ReminderFamily.SUGGESTION),
    VET_VISIT_D_MINUS_1(2, false, ReminderRoute.PAGE, ReminderFamily.VET_VISIT),
    VET_VISIT_D_DAY(2, false, ReminderRoute.FORM, ReminderFamily.VET_VISIT),
    STITCH_CHECK(5, true, ReminderRoute.PAGE, ReminderFamily.NEUTER),
    HEAT_DOG_D_MINUS_7(6, true, ReminderRoute.PAGE, ReminderFamily.HEAT),
    PYOMETRA_WATCH(1, false, ReminderRoute.PAGE, ReminderFamily.HEAT),
    HEAT_CAT_SECOND(8, true, ReminderRoute.PAGE, ReminderFamily.HEAT),
    HEAT_MALE_FIRST(8, true, ReminderRoute.PAGE, ReminderFamily.HEAT),
    SPAY_NEUTER_SUGGESTION(8, true, ReminderRoute.FORM_DATE_EMPTY, ReminderFamily.SUGGESTION),
    CHECKUP(7, true, ReminderRoute.FORM_DATE_EMPTY, ReminderFamily.CHECKUP),
    CUSTOM_D_DAY(2, false, ReminderRoute.FORM, ReminderFamily.CUSTOM);

    private final int tier;
    private final boolean movable;
    private final ReminderRoute route;
    private final ReminderFamily family;

    ReminderKind(int tier, boolean movable, ReminderRoute route, ReminderFamily family) {
        this.tier = tier;
        this.movable = movable;
        this.route = route;
        this.family = family;
    }

    public int tier() {
        return tier;
    }

    public boolean movable() {
        return movable;
    }

    public ReminderRoute route() {
        return route;
    }

    public ReminderFamily family() {
        return family;
    }

    /** 附录 C 行序（0 起）。 */
    public int catalogueOrder() {
        return ordinal();
    }

    /** 档 1–2 不占每宠每日一条的名额、不受生日 / 纪念日影响（FR-166）。 */
    public boolean capExempt() {
        return tier <= 2;
    }

    /** 驱虫类（含首次驱虫建议）：点开时的安全判定范围（FR-160 / FR-167）。 */
    public boolean dewormRelated() {
        return family == ReminderFamily.DEWORM || this == FIRST_DEWORM;
    }

    /** 发情 / 子宫蓄脓 / 绝育建议：已绝育宠物一律不要。 */
    public boolean droppedWhenNeutered() {
        return family == ReminderFamily.HEAT || this == SPAY_NEUTER_SUGGESTION;
    }

    /** 埋点用小写蛇形。 */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 地基已建的通知类型（{@code HEALTH_} + 本名），不改 {@code NotificationType}。 */
    public NotificationType notificationType() {
        return NotificationType.valueOf("HEALTH_" + name());
    }
}
