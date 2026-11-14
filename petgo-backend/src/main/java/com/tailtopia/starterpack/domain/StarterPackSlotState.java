package com.tailtopia.starterpack.domain;

/** 档 2 / 3 下单前的名额状态（查询接口 {@code slotState}，D-22 ④）。 */
public enum StarterPackSlotState {
    /** 名额 &lt; 5：会新建 1 个。 */
    GRANT,
    /** 名额已 5 个但有生效中的租用名额：会延长最早到期的那个。 */
    EXTEND,
    /** 名额已 5 个且无租用名额：不能买（409 starter-pack-slots-full）。 */
    FULL
}
