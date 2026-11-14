package com.tailtopia.entitlement.domain;

/** 使用场景（Story 4-1 AC2.4）：wire 值即 {@code entitlement_credit_uses.use_kind}。 */
public enum CreditUseKind {
    KTP_HD(CreditFamily.KTP_HD), TAILSONALITY(CreditFamily.TAILSONALITY), GIFT(CreditFamily.GIFT_1K);

    private final CreditFamily family;

    CreditUseKind(CreditFamily family) {
        this.family = family;
    }

    /** 本场景只能扣的大类。 */
    public CreditFamily family() {
        return family;
    }
}
