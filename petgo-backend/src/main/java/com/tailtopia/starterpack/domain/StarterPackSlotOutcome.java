package com.tailtopia.starterpack.domain;

/** 礼包名额发放结果（D-23，落库 {@code slot_outcome}）。 */
public enum StarterPackSlotOutcome {
    /** 档 1：不含名额。 */
    NONE,
    /** 新建 1 个 {@code PACK_GRANT} 租用名额（30 天）。 */
    GRANTED,
    /** 已满 5 个：最早到期的租用名额 +30 天。 */
    EXTENDED,
    /** 已满 5 个且无可延长的租用名额（下单后升级的竞态，待拍板 Q-10）：次数照发，名额交人工。 */
    SKIPPED
}
