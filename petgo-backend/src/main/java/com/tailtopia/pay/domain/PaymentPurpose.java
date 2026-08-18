package com.tailtopia.pay.domain;

/**
 * 支付用途（Story 1.1，落库 varchar(16) + CHECK）。一切收费场景共用同一意图基座：
 * 兽医咨询（Epic 3）、PawCoin 充值（Story 1.3）、AI 解锁（Epic 2）、身份证高清（Epic 6）。
 */
public enum PaymentPurpose {
    VET_CONSULT,
    PAWCOIN_TOPUP,
    AI_UNLOCK,
    ID_HD
}
