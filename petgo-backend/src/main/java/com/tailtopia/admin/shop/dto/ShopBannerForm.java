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

    /** 点击跳转类型（V1.4.1）：NONE / PRODUCT / CATEGORY / EXTERNAL / COUPON（v1.4.2）；空 = NONE。 */
    private String targetType;

    /** 跳转目标：商品 token / 品类字面量 / https 链接 / 券模板 public_token（v1.4.2 COUPON）。 */
    private String targetValue;

    /** 排期起（含），null = 不限（v1.4.2 shop Story 6.1，D-16）。 */
    private java.time.Instant startAt;

    /**
     * 页面控件（V1.4.2 shop-admin Story 3.1 · B3）：上 / 下线时间按 <b>WIB</b> 墙上时间录入（datetime-local），
     * {@link #getStartAt()} / {@link #getEndAt()} 优先取它们换算成绝对时刻；不填 = 不限。
     */
    @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private java.time.LocalDateTime startLocal;

    @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private java.time.LocalDateTime endLocal;

    public java.time.LocalDateTime getStartLocal() {
        return startLocal;
    }

    public void setStartLocal(java.time.LocalDateTime startLocal) {
        this.startLocal = startLocal;
    }

    public java.time.LocalDateTime getEndLocal() {
        return endLocal;
    }

    public void setEndLocal(java.time.LocalDateTime endLocal) {
        this.endLocal = endLocal;
    }

    private static java.time.Instant wib(java.time.LocalDateTime t) {
        return t == null ? null : t.atZone(com.tailtopia.shared.schedule.ScheduleWindow.WIB).toInstant();
    }

    /** 排期止（不含），null = 不限。 */
    private java.time.Instant endAt;

    public java.time.Instant getStartAt() {
        return startLocal != null ? wib(startLocal) : startAt;
    }

    public void setStartAt(java.time.Instant startAt) {
        this.startAt = startAt;
    }

    public java.time.Instant getEndAt() {
        return endLocal != null ? wib(endLocal) : endAt;
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
}
