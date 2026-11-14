package com.tailtopia.entitlement.service;

import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;

/**
 * 发放一批权益次数的命令（V1.5.0 gift-pack 架构 §5，<b>字段顺序冻结</b>，⑥ 依赖）。
 *
 * @param sourceRef PACK = 购买 id；ACTIVITY = 活动 id；ADMIN = 补发 grantToken（≤ 64）
 * @param idemKey   幂等键（≤ 160）；与 {@code type} 一起唯一，同一键可发多种类型
 * @param labelId   来源名快照（印尼语，≤ 80，可空）
 * @param labelEn   来源名快照（英语，≤ 80，可空）
 */
public record GrantCommand(long userId, CreditType type, int quantity, CreditSource source, String sourceRef,
        String idemKey, String labelId, String labelEn, Long operatorAdminId, String reason) {

    /** ⑥ 用这个：sourceRef = 活动 id，label = 活动名（印尼语 / 英语快照，≤ 80）。 */
    public static GrantCommand activity(long userId, CreditType type, int quantity, long activityId, String idemKey,
            String nameId, String nameEn) {
        return new GrantCommand(userId, type, quantity, CreditSource.ACTIVITY, String.valueOf(activityId), idemKey,
                nameId, nameEn, null, null);
    }
}
