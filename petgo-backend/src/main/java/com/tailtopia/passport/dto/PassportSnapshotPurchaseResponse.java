package com.tailtopia.passport.dto;

import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;

/**
 * 发起快照购买的响应（V1.3.2 Story 3.4 · AC3.4）：{@link KeepsakePurchaseResponse} 同形 + {@code snapshotToken}。
 */
public record PassportSnapshotPurchaseResponse(boolean unlocked, PaymentIntentResponse payment, String payload,
        String purchaseToken, String snapshotToken) {

    public static PassportSnapshotPurchaseResponse of(KeepsakePurchaseResponse r, String snapshotToken) {
        return new PassportSnapshotPurchaseResponse(r.unlocked(), r.payment(), r.payload(), r.purchaseToken(),
                snapshotToken);
    }
}
