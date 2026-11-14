package com.tailtopia.profile.domain;

import java.util.List;

/**
 * 7 个新手任务（V1.5.0 onboarding-recall Story 2.1 · FR-176 · OR-1），**按账号**判定、做过即记、永不撤。
 *
 * <p>{@link #name()} 即落库 {@code user_newbie_marks.task_key} 与对外 {@code key}（App 按 key 本地化标签）；
 * 声明序 = 固定展示顺序；{@link #ordinalForAnalytics()} = 对外序号 1–7（埋点 {@code task}）。
 *
 * <p>①–⑥ 由事件监听写标记（{@code NewbieTaskListener}）；⑦ 读进度时按 WIB 注册日直接判定，
 * 另由 {@code NewbieWeekJob} 每日补记标记（召回分层 / 毕业判定读标记）。
 *
 * <p>⚠️ V1.5.0 之前的 6 项（S1–S5 + 健康记录）整体搬到 {@code LegacyNewbieTaskCompat}（< 1.5.0 客户端专用，OR-9）。
 */
public enum NewbieTask {

    /** ① 发出第一篇 Diary（{@code GROWTH_MOMENT}，公开 / 私密都算）。 */
    FIRST_DIARY,
    /** ② 收到第一个赞（他人给我的帖子点赞；不排除虚拟号 / 自动点赞）。 */
    FIRST_LIKE_RECEIVED,
    /** ③ 关注一个人或追更一只宠物（系统自动关注不算）。 */
    FIRST_FOLLOW,
    /** ④ 送出第一个赞（给别人的帖子）。 */
    FIRST_LIKE_GIVEN,
    /** ⑤ 第一条评论（别人帖子下的一级评论）。 */
    FIRST_COMMENT,
    /** ⑥ 回复一条评论（被回复人不是自己）。 */
    FIRST_REPLY,
    /** ⑦ 注册满一周（WIB 注册日 + 7 ≤ 今天）。 */
    ONE_WEEK;

    /** 对外序号 1–7（埋点 {@code newbie_task_completed.task}）。 */
    public int ordinalForAnalytics() {
        return ordinal() + 1;
    }

    /** 固定展示顺序（枚举声明序）。 */
    public static final List<NewbieTask> ALL = List.of(values());
}
