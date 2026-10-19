package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.RuleCode;

/**
 * 后台对单个用户的一次 EXP 调整（V1.4.0 ① Story 4.2 · AD-5 adjust）。只经 {@link GrowthLedgerService#adjust} 落账。
 *
 * @param userId          目标用户
 * @param type            普通调整 / 运营活动（= 流水 {@code rule_code}）
 * @param delta           增减值（带符号，≠ 0）
 * @param note            原因或活动名（必填，≤ 200，原样落 {@code growth_ledger.note}）
 * @param operatorAdminId 操作人后台账号 id
 * @param grantToken      页面渲染时生成的一次性 token（幂等键 {@code ADMIN:{token}}，只经 {@link GrowthIdemKeys#admin}）；批量为 null
 * @param batchNo         批量发放的批次号（Story 4.3；非 null 时幂等键 = {@link GrowthIdemKeys#batch}，流水记 {@code batch_no}）
 */
public record AdminAdjustCommand(long userId, AdjustType type, int delta, String note, long operatorAdminId,
        String grantToken, String batchNo) {

    /** 单个调整（Story 4.2）。 */
    public AdminAdjustCommand(long userId, AdjustType type, int delta, String note, long operatorAdminId,
            String grantToken) {
        this(userId, type, delta, note, operatorAdminId, grantToken, null);
    }

    /** 批量发放的一人（Story 4.3）：恒为运营活动、只增。 */
    public static AdminAdjustCommand batch(long userId, int expPerUser, String campaign, long operatorAdminId,
            String batchNo) {
        return new AdminAdjustCommand(userId, AdjustType.OPS_CAMPAIGN, expPerUser, campaign, operatorAdminId, null,
                batchNo);
    }

    /** 本条命令的幂等键（只经 {@link GrowthIdemKeys}）。 */
    public String idemKey() {
        return batchNo != null ? GrowthIdemKeys.batch(batchNo, userId) : GrowthIdemKeys.admin(grantToken.trim());
    }

    /** 调整类型。名称即流水 {@code rule_code}。 */
    public enum AdjustType {
        ADMIN_ADJUST,
        OPS_CAMPAIGN;

        public RuleCode rule() {
            return RuleCode.valueOf(name());
        }

        /** 表单值 → 类型；空 / 未知 → null。 */
        public static AdjustType fromOrNull(String raw) {
            if (raw == null) {
                return null;
            }
            for (AdjustType t : values()) {
                if (t.name().equals(raw.trim())) {
                    return t;
                }
            }
            return null;
        }
    }

    /**
     * 调整结果。{@code replayed = true}：同一 token 已入过账，本次什么都没改（四个数都是当前值）。
     */
    public record Result(long oldTotal, long newTotal, int oldLevel, int newLevel, boolean replayed) {
    }
}
