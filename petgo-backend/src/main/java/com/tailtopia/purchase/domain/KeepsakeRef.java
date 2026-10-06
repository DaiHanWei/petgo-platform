package com.tailtopia.purchase.domain;

/**
 * 购买对象（V1.3.2 Story 3.1）：由 SKU 模块在<b>加锁读业务行之后</b>构造传入。
 *
 * @param refId           业务行 id（{@code keepsake_purchases.ref_id}）
 * @param refToken        业务行对外 token（幂等键基于它，<b>禁止</b>用 petId / userId）
 * @param petProfileId    购买时的宠物（记录用，可空）
 * @param alreadyUnlocked 业务行此刻是否已解锁（true → 409 {@code keepsake-already-unlocked}）
 */
public record KeepsakeRef(KeepsakeSku sku, long refId, String refToken, Long petProfileId, boolean alreadyUnlocked) {
}
