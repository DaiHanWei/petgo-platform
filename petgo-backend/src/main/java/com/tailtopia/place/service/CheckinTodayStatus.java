package com.tailtopia.place.service;

import java.util.List;

/**
 * 场所详情「今日已打卡」逐宠物结果（V1.4.0 multi-pet Story 1.3 · AD-13，决策日志「今日已打卡按宠物算」）。
 *
 * @param allCheckedIn    本人有宠物且全部宠物今天（WIB）都已在该场所打卡（Story 2.1 起改为「全部 ACTIVE 宠物」）
 * @param checkedInPetIds 本人宠物中今天已在该场所打卡的宠物 id（按账号宠物顺序）
 */
public record CheckinTodayStatus(boolean allCheckedIn, List<Long> checkedInPetIds) {

    public CheckinTodayStatus {
        checkedInPetIds = checkedInPetIds == null ? List.of() : List.copyOf(checkedInPetIds);
    }
}
