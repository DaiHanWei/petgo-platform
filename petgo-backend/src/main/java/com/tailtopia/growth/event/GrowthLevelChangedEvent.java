package com.tailtopia.growth.event;

/**
 * 用户 Tailrent Level 变化领域事件（V1.4.0，过去式）。升级、降级都发；一次连跳多级只发一条（新旧为首尾等级）。
 *
 * <p>本类由 Story 0.1 地基定义：发布方 ①（{@code feat/1.4.0-tailrent-level}）、消费方 ②（{@code feat/1.4.0-multi-pet}，
 * 重算宠物名额）<b>只引用、不重定义、不改字段</b>。经 {@code ApplicationEventPublisher} 进程内发布。
 *
 * @param userId   等级变化的用户 id
 * @param oldLevel 变化前等级
 * @param newLevel 变化后等级
 */
public record GrowthLevelChangedEvent(long userId, int oldLevel, int newLevel) {
}
