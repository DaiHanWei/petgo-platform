package com.tailtopia.purchase.domain;

import com.tailtopia.pay.domain.PaymentPurpose;
import java.util.Optional;

/** 一次性解锁 SKU（与 {@link PaymentPurpose} 同名，落库 {@code keepsake_purchases.sku}）。 */
public enum KeepsakeSku {
    TAILSONALITY,
    PASSPORT_SNAP,
    BOARDING_PASS;

    public PaymentPurpose toPurpose() {
        return PaymentPurpose.valueOf(name());
    }

    /** 支付用途 → SKU；非三个一次性解锁用途 → empty。 */
    public static Optional<KeepsakeSku> fromPurpose(PaymentPurpose purpose) {
        if (purpose == null) {
            return Optional.empty();
        }
        for (KeepsakeSku s : values()) {
            if (s.name().equals(purpose.name())) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
