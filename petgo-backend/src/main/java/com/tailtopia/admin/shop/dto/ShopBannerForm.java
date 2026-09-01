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
