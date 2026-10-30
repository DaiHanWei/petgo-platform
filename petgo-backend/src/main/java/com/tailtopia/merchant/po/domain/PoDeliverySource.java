package com.tailtopia.merchant.po.domain;

/** 进货单签收走的出口（只作留痕）。门店端没有「确认收货」按钮，故比 Toko 少一条用户确认出口。 */
public enum PoDeliverySource {
    /** TailTopia ops 手工标记送达。 */
    ADMIN_MARK,
    /** 发货起 {@code PurchaseOrder#AUTO_DELIVER_AFTER} 仍未标记，兜底置送达（沿用 Toko 7 日）。 */
    AUTO_TIMEOUT
}
