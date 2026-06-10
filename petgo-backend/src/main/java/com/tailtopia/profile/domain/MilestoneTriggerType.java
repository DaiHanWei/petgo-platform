package com.tailtopia.profile.domain;

/**
 * 里程碑触发方式（FR-42，落库 UPPER_SNAKE）。决定列表页点击徽章的交互（8.2）与完成路径（8.3/8.4/8.6）。
 *
 * <ul>
 *   <li>{@link #SYSTEM_AUTO} 系统自动：订阅既有领域事件 / 定时计数自动点亮（8.3），点击徽章仅弹说明文案。</li>
 *   <li>{@link #USER_CHECKIN} 用户打卡：点击灰徽章 → 「已打卡」关联成长日历内容 / 「去发布」（8.4）。</li>
 *   <li>{@link #PUSH_PUBLISH} 系统推送 + 用户当天发布：节点当天推送提醒（6.7/8.6）+ 当天发布成长日历自动完成。</li>
 * </ul>
 */
public enum MilestoneTriggerType {
    SYSTEM_AUTO,
    USER_CHECKIN,
    PUSH_PUBLISH
}
