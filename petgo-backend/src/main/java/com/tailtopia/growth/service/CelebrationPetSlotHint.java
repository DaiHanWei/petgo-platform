package com.tailtopia.growth.service;

/**
 * 升级庆祝的「永久名额提示」判定口（V1.4.0 multi-pet Story 4.4 · AD-16）。
 *
 * <p>模块方向：{@code growth} 不依赖 {@code profile.slot}——本接口在 growth 侧定义、由多宠物线在
 * {@code profile.slot} 实现（名额门槛只经 {@code PetSlotService}，AD-5）。{@link GrowthPopupService} 可选注入，
 * 缺实现时恒 {@code false}。
 */
public interface CelebrationPetSlotHint {

    /**
     * 本次升级（读时校正后的 {@code fromLevel → toLevel}）获得了等级名额，且此刻有生效中的月租名额。
     */
    boolean petSlotHint(long userId, int fromLevel, int toLevel);
}
