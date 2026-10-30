package com.tailtopia.merchant.po.domain;

/** 运费方式（契约 5.2）：由商家平台按其包邮门槛判定并随建单传入，本仓库原样保存、不另维护门槛（FR-11）。 */
public enum PoShippingMethod {
    /** 包邮。 */
    FREE,
    /** 运费到付：平台不收运费，{@code goods_amount} 不含运费。 */
    COD_SHIPPING
}
