package com.tailtopia.admin.seed.service;

import com.tailtopia.content.domain.ImageSize;

/**
 * 上传图会被 Feed 裁掉多少 —— 算给运营看（V1.1.6 Story 12.2 · AC3 · AB-3J）。
 *
 * <h2>🔴 这个类为什么存在</h2>
 * App 端上传超比例图会弹裁剪框、由用户自选构图（FR-71）；**后台没有这一步**。
 * 超比例图会静默进库，然后在 Feed 渲染时被 clamp 到 {@code 0.75~1.34} 并 cover 裁切 ——
 * <b>运营完全不知道自己的图会被裁掉多少，构图可能被毁</b>（比如一张左右两只猫的横图，
 * 上传后 Feed 里只剩中间）。
 *
 * <p>🛡 <b>只警告，不阻止</b>（A-13：后台不做裁剪框）。运营在桌面端工作、手上有外部裁图工具；
 * 内置裁剪器是为手机端用户存在的。当前的问题不是运营不能裁，而是<b>不知道会被裁</b>。
 *
 * <h2>裁切量怎么算</h2>
 * Feed 用 cover 填充一个比例被 clamp 后的容器：
 * <ul>
 *   <li>图太宽（{@code r > 1.34}）⇒ 容器按高对齐，<b>左右</b>被裁：可见宽度占 {@code 1.34 / r}</li>
 *   <li>图太高（{@code r < 0.75}）⇒ 容器按宽对齐，<b>上下</b>被裁：可见高度占 {@code r / 0.75}</li>
 * </ul>
 *
 * <p>⚠️ <b>报的是"共裁掉多少"与"每侧多少"两个数</b>，因为这两个数差一倍，
 * 只给一个必然被读错。story 里的示例文案「16:9 …… 左右各裁切约 25%」正是这个歧义：
 * 16:9 的正确结论是 <b>共裁约 25%、每侧约 12%</b>，写成"各 25%"会让运营以为要裁掉一半。
 */
public final class ImageRatioAdvisor {

    /** 与 App 端 clamp 区间逐字一致（FR-71 / AD-6）。改这里必须同时改客户端，否则两边判读不一致。 */
    public static final double MIN_RATIO = 0.75;
    public static final double MAX_RATIO = 1.34;

    /** 裁切方向。 */
    public enum Crop {
        /** 落在区间内，不裁。 */
        NONE,
        /** 图太宽，左右被裁。 */
        SIDES,
        /** 图太高，上下被裁。 */
        TOP_BOTTOM
    }

    /**
     * 一张图的裁切预判。
     *
     * @param ratio        原始宽高比（宽 ÷ 高）
     * @param crop         裁切方向
     * @param totalPercent 共被裁掉的百分比（整数，四舍五入）
     * @param perSidePercent 每侧被裁掉的百分比（整数，四舍五入）——与 {@code totalPercent} 差一倍，
     *                       两个都报是刻意的，见类注释
     */
    public record Advice(double ratio, Crop crop, int totalPercent, int perSidePercent) {

        public boolean warns() {
            return crop != Crop.NONE;
        }
    }

    private ImageRatioAdvisor() {
    }

    /**
     * 算一张图的裁切预判。
     *
     * <p>尺寸测不出来（{@code null} 或不合理）时返回 {@link Crop#NONE} ——
     * 🛡 <b>不猜、也不因此拦住上传</b>：测不出尺寸的原因通常是格式冷门，
     * 而那和"这张图会不会被裁"是两件事。
     */
    public static Advice advise(ImageSize size) {
        if (size == null || !size.isReasonable()) {
            return new Advice(0, Crop.NONE, 0, 0);
        }
        double r = (double) size.w() / size.h();
        if (r > MAX_RATIO) {
            // 按高对齐 ⇒ 可见宽度占 MAX_RATIO / r。
            return advice(r, Crop.SIDES, 1 - MAX_RATIO / r);
        }
        if (r < MIN_RATIO) {
            // 按宽对齐 ⇒ 可见高度占 r / MIN_RATIO。
            return advice(r, Crop.TOP_BOTTOM, 1 - r / MIN_RATIO);
        }
        return new Advice(r, Crop.NONE, 0, 0);
    }

    private static Advice advice(double ratio, Crop crop, double cutFraction) {
        int total = (int) Math.round(cutFraction * 100);
        return new Advice(ratio, crop, total, (int) Math.round(cutFraction * 50));
    }
}
