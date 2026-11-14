package com.tailtopia.notify;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * V1.5.0 地基 Story 0.1 一次加进 {@code ck_notifications_type} 的 27 个通知类型（测试共用）。
 * 旧的迁移守卫（V1.4.2）比对「枚举 = 当时全集」时先减去这批，最新全集的比对归 {@link V150NotifyMigrationTest}。
 */
public final class V150NotificationTypes {

    /** 健康记录提醒 23 种（PRD 附录 C 的提醒 ID 加 {@code HEALTH_} 前缀）。 */
    public static final List<String> HEALTH = List.of(
            "HEALTH_VACCINE_D_MINUS_7",
            "HEALTH_VACCINE_D_DAY",
            "HEALTH_VACCINE_D_PLUS_7",
            "HEALTH_FIRST_VACCINE",
            "HEALTH_DEWORM_INTERNAL_D_DAY",
            "HEALTH_DEWORM_EXTERNAL_D_DAY",
            "HEALTH_DEWORM_FLEA_WORM_D_DAY",
            "HEALTH_DEWORM_HEARTWORM_D_DAY",
            "HEALTH_DEWORM_INTERNAL_D_PLUS_3",
            "HEALTH_DEWORM_EXTERNAL_D_PLUS_3",
            "HEALTH_DEWORM_LEGACY_D_DAY",
            "HEALTH_DEWORM_LEGACY_D_PLUS_3",
            "HEALTH_FIRST_DEWORM",
            "HEALTH_VET_VISIT_D_MINUS_1",
            "HEALTH_VET_VISIT_D_DAY",
            "HEALTH_STITCH_CHECK",
            "HEALTH_HEAT_DOG_D_MINUS_7",
            "HEALTH_PYOMETRA_WATCH",
            "HEALTH_HEAT_CAT_SECOND",
            "HEALTH_HEAT_MALE_FIRST",
            "HEALTH_SPAY_NEUTER_SUGGESTION",
            "HEALTH_CHECKUP",
            "HEALTH_CUSTOM_D_DAY");

    /** 地基新增全集：健康 23 + 推送与召回 1 + 超值礼包 1 + 运营活动 2。 */
    public static final Set<String> ADDED;

    static {
        Set<String> s = new LinkedHashSet<>(HEALTH);
        s.add("USER_POST_PUBLISHED");
        s.add("PACK_CREDIT_EXPIRING");
        s.add("OPS_ACTIVITY_REWARDED");
        s.add("OPS_ACTIVITY_RANK_WON");
        ADDED = Set.copyOf(s);
    }

    private V150NotificationTypes() {
    }
}
