package com.tailtopia.admin.shop.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * banner 图比例检查（V1.4.2 shop-admin Story 3.1 · AD-7 · B6）：App banner 显示尺寸 352 × 148（约 2.4:1）。
 * {@code |w/h − 352/148| / (352/148) > 3%} 时提醒（偏高 = 上下被裁 / 偏宽 = 左右被裁），<b>不拦截</b>。
 * 前端 {@code admin-core.js [data-banner-ratio]} 用同一阈值在上传后即时提示，这里是服务端（编辑态回显）与 L0 单测入口。
 */
public final class BannerImageRatio {

    public static final int APP_W = 352;
    public static final int APP_H = 148;
    /** 允许偏差 3%。 */
    public static final double TOLERANCE = 0.03;

    /** OK = 比例在 ±3% 内；TALL = 比 App 高（上下被裁）；WIDE = 比 App 宽（左右被裁）。 */
    public enum Fit {
        OK, TALL, WIDE
    }

    /** @param ratio 展示用比例（{@code w/h} 保留一位小数，如 {@code 2.4:1}） */
    public record Result(Fit fit, int width, int height, String ratio) {
    }

    private BannerImageRatio() {
    }

    public static Result check(int width, int height) {
        if (width <= 0 || height <= 0) {
            return new Result(Fit.OK, width, height, "—");
        }
        double target = (double) APP_W / APP_H;
        double actual = (double) width / height;
        double dev = (actual - target) / target;
        Fit fit = Math.abs(dev) <= TOLERANCE ? Fit.OK : (dev < 0 ? Fit.TALL : Fit.WIDE);
        String ratio = BigDecimal.valueOf(actual).setScale(1, RoundingMode.HALF_UP).toPlainString() + ":1";
        return new Result(fit, width, height, ratio);
    }
}
