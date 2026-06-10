package com.tailtopia.profile.domain;

/**
 * 里程碑完成来源（FR-42，落 {@code milestone_completions.source} UPPER_SNAKE）。记录该完成由何路径产生。
 *
 * <ul>
 *   <li>{@link #SYSTEM_AUTO} 系统自动：领域事件订阅 / 计数 / 组合依赖自动点亮（8.3）。</li>
 *   <li>{@link #USER_CHECKIN} 用户打卡：「已打卡」关联一条既有成长日历内容（8.4）。</li>
 *   <li>{@link #PUBLISH} 去发布 / 推送当天发布：发布一条成长日历内容后回填完成（8.4/8.6）。</li>
 * </ul>
 */
public enum MilestoneCompletionSource {
    SYSTEM_AUTO,
    USER_CHECKIN,
    PUBLISH
}
