package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.PetProfile;

/**
 * 旧版客户端（请求头 &lt; 1.5.0 或缺头）写健康记录时的默认值调用点（V1.5.0 健康记录提醒 Story 1.1 留口，
 * 架构增量 §6.1「旧版客户端」）。
 *
 * <p>旧版 App 不认识新字段：新建时由这里写默认 Next date / 关键词匹配预设（Story 1.2 接入 {@code NextDateRules}），
 * 编辑时按「改了疫苗名 → 重匹配、清剂次；改了日期使 next_date ≤ eventDate → 重算」处理（Story 1.3）。
 * 其余情况新字段一律不动。
 */
public final class HealthRecordDefaults {

    /** 本次旧版写入改了什么（新建时 {@code created=true}，其余两项无意义）。 */
    public record LegacyChange(boolean created, boolean vaccineNameChanged, boolean eventDateChanged) {

        public static LegacyChange ofCreate() {
            return new LegacyChange(true, false, false);
        }
    }

    private HealthRecordDefaults() {
    }

    /**
     * 旧版客户端写入后补默认值。
     *
     * <p>TODO(v1.5.0 Story 1.2)：新建 → 关键词匹配预设 + 默认 next_date；
     * TODO(v1.5.0 Story 1.3)：编辑 → 改名重匹配 / 改日期重算。Story 1.1 先空实现。
     */
    public static void applyForLegacyClient(PetProfile pet, HealthRecord record, LegacyChange change) {
        // Story 1.1：空实现（调用点已接好）。
    }
}
