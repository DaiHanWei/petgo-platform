package com.tailtopia.admin.shop.dto;

import com.tailtopia.shop.domain.ReturnPolicy;

/**
 * SKU 创建/编辑表单（Story 1.3，AB-10B）。
 *
 * <p>🔴 {@code price} / {@code costPrice} 为<b>最小币种单位整型</b>（IDR 无小数，NFR-9）——
 * 表单展示 {@code Rp} 前缀与千分位，提交时转整型。禁 {@code DECIMAL}/{@code double}。
 *
 * <p>🔒 {@code costPrice} 是<b>商业敏感</b>：需 {@code shop.cost_edit} 权限；
 * 无权限时该字段在服务端就被忽略（不是前端隐藏）。
 *
 * <p>{@code returnPolicy} 可空 = 继承商品级（FR-94A）。
 */
public class ShopSkuForm {

    private Long id;
    private String specName;
    private Long price;
    private Long netWeightG;
    private Long costPrice;
    private ReturnPolicy returnPolicy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSpecName() { return specName; }
    public void setSpecName(String specName) { this.specName = specName; }
    public Long getPrice() { return price; }
    public void setPrice(Long price) { this.price = price; }
    public Long getNetWeightG() { return netWeightG; }
    public void setNetWeightG(Long netWeightG) { this.netWeightG = netWeightG; }
    public Long getCostPrice() { return costPrice; }
    public void setCostPrice(Long costPrice) { this.costPrice = costPrice; }
    public ReturnPolicy getReturnPolicy() { return returnPolicy; }
    public void setReturnPolicy(ReturnPolicy v) { this.returnPolicy = v; }
}
