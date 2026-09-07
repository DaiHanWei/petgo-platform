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
    SHOP_ORDER
}
