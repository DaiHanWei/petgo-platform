package com.tailtopia.content.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 一张图的原始宽高（V1.1.6 Story 3.1 · AD-5）。
 *
 * <p>🛡 <b>只有原始宽高，没有比例、没有高度。</b>
 * 展示比例的收敛（clamp 到 0.75~1.34）与高度护栏<b>一律在客户端渲染时施加</b> ——
 * 护栏依赖可视区高度，服务端算不了；而服务端先 clamp 一遍、客户端再 clamp 一遍
 * 就是<b>双重裁切</b>（AD-6 Rule 6）。
 *
 * <p>⚠️ 想在这里加 {@code ratio} 或 {@code displayHeight} 之前，先读一遍上面那段。
 *
 * @param w 原始宽（像素，必须 &gt; 0）
 * @param h 原始高（像素，必须 &gt; 0）
 */
public record ImageSize(int w, int h) {

    /**
     * 一张真实照片的像素上限。
     *
     * <p>超过这个数的多半不是照片，而是客户端算错了或有人手填了个荒唐值 ——
     * 与其把它当真（会让卡片高度失控），不如当作"测不出来"走兜底。
     * 取 30000 是因为目前消费级设备与常见全景图都远在其下。
     */
    public static final int MAX_REASONABLE_PX = 30000;

    /**
     * 明显不合理的尺寸一律当作测不出来。宽高必须为正，且不能大到不像真实照片。
     *
     * <p>⚠️ <b>必须标 {@code @JsonIgnore}</b>：本 record 会被序列化成 JSON 存进数据库、
     * 也会随接口下发。而 {@code isXxx()} 会被 Jackson 当成一个名为 {@code reasonable} 的
     * <b>幽灵字段</b>写进 JSON —— 回读时因为"未知字段"直接抛异常，发布整个失败。
     * （2026-08-18 实测踩到：发布接口 500。）
     *
     * <p>往这个 record 上加任何 {@code isXxx()} / {@code getXxx()} 之前，先想起这条。
     */
    @JsonIgnore
    public boolean isReasonable() {
        return w > 0 && h > 0 && w <= MAX_REASONABLE_PX && h <= MAX_REASONABLE_PX;
    }
}
