package com.tailtopia.growth.domain;

/**
 * 用于<b>权益判断</b>的当前等级（V1.4.0 ① 对外契约，Story 0.1 地基定义）。
 *
 * <p>{@code participating = false}（虚拟号、查无此人）时调用方<b>一律不给权益</b>：② 宠物名额只算默认 + 月租，
 * ③ 视为不能送专属礼物。展示用的等级（含虚拟号的运营设定展示等级）不走这里，由 {@code AuthorView} 下发。
 *
 * @param participating 是否参与成长体系
 * @param level         当前等级（不参与时为 0）
 * @param tier          当前段位（不参与时为 null）
 */
public record GrowthLevel(boolean participating, int level, GrowthTier tier) {

    /** 不参与成长体系：不享受任何等级权益。 */
    public static final GrowthLevel NOT_PARTICIPATING = new GrowthLevel(false, 0, null);

    public static GrowthLevel of(int level, GrowthTier tier) {
        return new GrowthLevel(true, level, tier);
    }
}
