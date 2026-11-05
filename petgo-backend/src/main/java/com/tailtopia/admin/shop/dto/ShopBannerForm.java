package com.tailtopia.admin.shop.dto;

/**
 * Toko banner 配置表单（2026-08-27）。
 *
 * <p>图片字段承载 OSS <b>对象 key</b>（前端经直传上传后回填 key），<b>绝不</b>承载 URL。
 * 校验在 service 层（照 {@code AdminShopProductService.validate} 范式）。
 *
 * <p>{@code imageW / imageH} 由上传控件自动回填。手填 objectKey 的兜底路径给不出尺寸，
 * 此时为 null —— 客户端按默认比例渲染，属可接受降级，不是错误。
 */
public class ShopBannerForm {

    private String imageKey;
    private Integer imageW;
    private Integer imageH;
    private int sortWeight;

    /** 点击跳转类型（V1.4.1）：NONE / PRODUCT / CATEGORY / EXTERNAL / COUPON（v1.4.2）；空 = NONE。 */
    private String targetType;

    /** 跳转目标：商品 token / 品类字面量 / https 链接 / 券模板 public_token（v1.4.2 COUPON）。 */
    private String targetValue;

    /** 排期起（含），null = 不限（v1.4.2 shop Story 6.1，D-16）。页面控件由后台线补。 */
    private java.time.Instant startAt;

    /** 排期止（不含），null = 不限。 */
    private java.time.Instant endAt;

    public java.time.Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(java.time.Instant startAt) {
        this.startAt = startAt;
    }

    public java.time.Instant getEndAt() {
        return endAt;
    }

    public void setEndAt(java.time.Instant endAt) {
        this.endAt = endAt;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(String targetValue) {
        this.targetValue = targetValue;
    }

    public String getImageKey() {
        return imageKey;
    }

    public void setImageKey(String imageKey) {
        this.imageKey = imageKey;
    }

    public Integer getImageW() {
        return imageW;
    }

    public void setImageW(Integer imageW) {
        this.imageW = imageW;
    }

    public Integer getImageH() {
        return imageH;
    }

    public void setImageH(Integer imageH) {
        this.imageH = imageH;
    }

    public int getSortWeight() {
        return sortWeight;
    }

    public void setSortWeight(int sortWeight) {
        this.sortWeight = sortWeight;
    }
}
