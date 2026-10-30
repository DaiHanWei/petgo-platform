package com.tailtopia.merchant.po.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 进货单明细：建单时写入后不改（商品名、规格名、主图快照）—— 历史进货单不随商品改名、换图或下架而变。
 * {@code lineNo} 按建单请求的行顺序从 1 起。
 */
@Entity
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_order_id", nullable = false, updatable = false)
    private Long purchaseOrderId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    /** {@code shop_skus.id}（内部用，不外露）。 */
    @Column(name = "sku_id", nullable = false, updatable = false)
    private Long skuId;

    @Column(name = "sku_token_snapshot", nullable = false, updatable = false, length = 32)
    private String skuToken;

    @Column(name = "product_name_snapshot", nullable = false, updatable = false, length = 120)
    private String productName;

    @Column(name = "spec_name_snapshot", nullable = false, updatable = false, length = 60)
    private String specName;

    /** 建单时 {@code shop_products.main_image_key}（objectKey，可空）。 */
    @Column(name = "image_key_snapshot", updatable = false, length = 255)
    private String imageKey;

    @Column(name = "wholesale_price", nullable = false, updatable = false)
    private long wholesalePrice;

    @Column(name = "qty", nullable = false, updatable = false)
    private int qty;

    @Column(name = "subtotal", nullable = false, updatable = false)
    private long subtotal;

    protected PurchaseOrderLine() {
    }

    public static PurchaseOrderLine of(long purchaseOrderId, int lineNo, long skuId, String skuToken,
            String productName, String specName, String imageKey, long wholesalePrice, int qty) {
        PurchaseOrderLine l = new PurchaseOrderLine();
        l.purchaseOrderId = purchaseOrderId;
        l.lineNo = lineNo;
        l.skuId = skuId;
        l.skuToken = skuToken;
        l.productName = productName;
        l.specName = specName;
        l.imageKey = imageKey;
        l.wholesalePrice = wholesalePrice;
        l.qty = qty;
        l.subtotal = wholesalePrice * qty;
        return l;
    }

    public Long getId() {
        return id;
    }

    public Long getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public Long getSkuId() {
        return skuId;
    }

    public String getSkuToken() {
        return skuToken;
    }

    public String getProductName() {
        return productName;
    }

    public String getSpecName() {
        return specName;
    }

    public String getImageKey() {
        return imageKey;
    }

    public long getWholesalePrice() {
        return wholesalePrice;
    }

    public int getQty() {
        return qty;
    }

    public long getSubtotal() {
        return subtotal;
    }
}
