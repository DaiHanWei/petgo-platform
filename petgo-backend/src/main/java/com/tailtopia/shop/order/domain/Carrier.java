package com.tailtopia.shop.order.domain;

/**
 * 承运商（Story 4.1 / 4.2，三选一）。
 *
 * <p>⚠️ <b>承运商不是运费维度</b>（C-14 后运费表已收为区域一维）—— 它只在发货时选择并留痕，
 * 不参与任何金额计算。把它当维度会让运费表重新变成二维，而二维表正是 C-14 砍掉的东西。
 *
 * <p>🔴 <b>不接承运商 API、不在 App 内渲染物流轨迹</b>（FR-103）：{@link #trackingUrl} 给的是
 * 承运商官网地址，跳出去查 —— 自建轨迹聚合要为三家 API 的可用性长期负责，V1 不承担这个。
 */
public enum Carrier {

    JNE("JNE", "https://www.jne.co.id/tracking-package"),
    SICEPAT("SiCepat", "https://www.sicepat.com/checkAwb"),
    ANTERAJA("Anteraja", "https://anteraja.id/tracking");

    private final String displayName;
    private final String trackingUrl;

    Carrier(String displayName, String trackingUrl) {
        this.displayName = displayName;
        this.trackingUrl = trackingUrl;
    }

    /** 展示名（大小写按品牌官方写法，不等于枚举名）。 */
    public String displayName() {
        return displayName;
    }

    /** 承运商官网查询页。App 侧只做跳转，不解析页面内容。 */
    public String trackingUrl() {
        return trackingUrl;
    }

    /**
     * 宽松解析（后台表单 / 历史数据）。
     *
     * <p>🔴 <b>未知值抛错而非默认到某一家</b> —— 与「未知枚举降级到最保守档」不同：
     * 承运商没有「保守档」，猜错等于把包裹记到别人家的运单号上，用户点进去查无此单。
     */
    public static Carrier parse(String raw) {
        if (raw != null) {
            for (Carrier c : values()) {
                if (c.name().equalsIgnoreCase(raw.trim())) {
                    return c;
                }
            }
        }
        throw com.tailtopia.shared.error.AppException.validation("承运商只支持 JNE / SiCepat / Anteraja");
    }
}
