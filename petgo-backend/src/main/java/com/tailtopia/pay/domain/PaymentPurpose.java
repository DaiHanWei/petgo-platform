package com.tailtopia.pay.domain;

/**
 * 支付用途（Story 1.1，落库 varchar(16) + CHECK）。一切收费场景共用同一意图基座：
 * 兽医咨询（Epic 3）、PawCoin 充值（Story 1.3）、AI 解锁（Epic 2）、身份证高清（Epic 6）。
 */
public enum PaymentPurpose {
    VET_CONSULT,
    PAWCOIN_TOPUP,
    AI_UNLOCK,
    ID_HD,
    /**
     * 精选自营电商订单（V1.4.0 Story 3.8）。🔴 <b>只在枚举末尾追加</b>（并行契约 E-1）——
     * 中间插值会让另两条工作线的 ordinal 序静默错位。DB 侧 CHECK 见 V113。
     *
     * <p>这是唯一可能取 {@code MIXED} 渠道的用途：虚拟商品三处均显式拒绝混合支付（AD-3）。
     */
    SHOP_ORDER,
    /**
     * V1.3.2 batch-a Story 3.1（AD-1）：三类一次性解锁，与 {@code keepsake_purchases.sku} 同名。
     * 🔴 同样只在末尾追加；DB 侧 CHECK 全量重建见 V20260930_2152。到账唯一消费者 = {@code KeepsakePaidHandler}。
     */
    TAILSONALITY,
    PASSPORT_SNAP,
    BOARDING_PASS,
    /**
     * Tailsonality 配型单独解锁（2026-10-09）。🔴 末尾追加；列宽 VARCHAR(16) 放不下 {@code TAILSONALITY_MATCH}，故缩写。
     * DB 侧 CHECK 全量重建见 V20261009_0113。
     */
    TS_MATCH,
    /**
     * V1.4.0 ③ 送礼（Story 0.1 地基占位，送礼业务由 {@code feat/1.4.0-gift} 实现）。🔴 末尾追加。
     * <b>不走消费返成长值</b>，只走送礼规则 {@code GIFT_SENT} / {@code GIFT_RECEIVED}，避免同一笔算两次。
     * DB 侧 CHECK 全量重建见 V20261018_1401；四条线不再改该约束。
     */
    GIFT,
    /**
     * V1.4.0 ② 宠物名额月租（Story 0.1 地基占位，业务由 {@code feat/1.4.0-multi-pet} 实现）。🔴 末尾追加。
     * 成长值按数字服务档自动入账（① 负责），② 不写成长值逻辑。DB 侧 CHECK 见 V20261018_1401。
     */
    PET_SLOT_RENT
}
