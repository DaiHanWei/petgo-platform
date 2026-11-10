package com.tailtopia.shop.order.service;

import com.tailtopia.shop.cart.dto.CartView;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.logistics.PackageItem;
import com.tailtopia.shop.logistics.PackageItems;
import com.tailtopia.shop.order.dto.UnavailableLine;
import com.tailtopia.shop.order.pricing.PricingLine;

/**
 * 结算的一行商品（v1.4.2 shop Story 3.1）—— CART 与 BUY_NOW 共用同一个构造，避免两套字段；
 * 3.2 下单复用。
 *
 * @param unavailableReason {@code DELISTED | OUT_OF_STOCK | INSUFFICIENT_STOCK | NOT_SELLABLE}；有效为 null
 * @param returnPolicy      生效规则（SKU 未设继承商品，都没有取最严）
 * @param sku               对应 SKU 实体（包裹参数来源）；SKU 已不存在时为 null
 */
public record ResolvedCheckoutLine(
        String skuToken,
        String productToken,
        String productName,
        String specName,
        long price,
        int qty,
        String mainImageUrl,
        ReturnPolicy returnPolicy,
        String unavailableReason,
        Long availableStock,
        ProductCategory category,
        String entrySource,
        String triggerType,
        ShopSku sku) {

    public static final String REASON_DELISTED = CartView.REASON_DELISTED;
    public static final String REASON_OUT_OF_STOCK = CartView.REASON_OUT_OF_STOCK;
    public static final String REASON_NOT_SELLABLE = CartView.REASON_NOT_SELLABLE;
    public static final String REASON_INSUFFICIENT_STOCK = UnavailableLine.REASON_INSUFFICIENT_STOCK;

    /**
     * 是否进入计价与询价：有效行，以及「可售但库存不够本次数量」的行（它是用户真选中的商品，
     * 金额照算、只是不可提交）。下架 / 不可售 / 售罄的行不计价、不询价。
     */
    public boolean priced() {
        return unavailableReason == null || REASON_INSUFFICIENT_STOCK.equals(unavailableReason);
    }

    public PricingLine toPricingLine() {
        return new PricingLine(skuToken, productToken, category, price, qty);
    }

    /** 仅对 {@link #priced()} 行调用（可售已保证）。 */
    public PackageItem toPackageItem() {
        return PackageItems.of(sku, productName, qty);
    }
}
