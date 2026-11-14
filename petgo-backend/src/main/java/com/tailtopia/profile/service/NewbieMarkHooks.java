package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.NewbieTask;

/**
 * 新手任务标记「真正新写入」之后的扩展点（V1.5.0 onboarding-recall Story 2.1）。
 *
 * <p>实现：{@link NewbieGraduationService}（Story 2-2：7 项全满 → 解锁账号级 Lulus Pemula）；
 * {@link NewbieMarkService} 经 {@code ObjectProvider} 取（0 个即不调）。做成接口是为了让 2-1 不依赖 2-2。
 * 调用时机：{@link NewbieMarkService#markIfAbsent} 新写入<b>且提交后</b>；
 * {@code NewbieWeekJob} 写 {@link NewbieTask#ONE_WEEK} 后。
 */
public interface NewbieMarkHooks {

    void afterMarked(long userId, NewbieTask task);
}
