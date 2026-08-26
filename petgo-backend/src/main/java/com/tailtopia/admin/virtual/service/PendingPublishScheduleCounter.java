package com.tailtopia.admin.virtual.service;

/**
 * 「该账号还有多少条待发布排期」的读取口（V1.1.6 Story 12.1 · AC4）。
 *
 * <h2>🔴 为什么是一个接口，而不是直接查表</h2>
 * <b>内容排期这个概念在本 story 落地时还不存在</b> —— 它是 Story 13.1（草稿与排期状态机）
 * 建的模型、13.5（定时发布）填的数据。而 AC4 要求的提示逻辑属于本 story：
 * 「移出前必须告诉运营，这个号还有 N 条排期到点会失败」。
 *
 * <p>所以这里把"数排期"这件事收成一个口子：12.1 交付提示的**机制与护栏**
 * （不阻止移出、不自动取消、不自动转草稿），实现换一个就接上了。
 * 🛡 <b>不要在别处另写一处提示</b> —— 那样虚拟账号禁用与真实账号移出会各判一次，
 * 两处口径迟早分叉。
 *
 * <p>✅ <b>V1.1.6 Story 13.1 已接上真实数据</b>（{@link ScheduledSeedRowCounter}，
 * 数 {@code seed_batch_rows} 里 SCHEDULED 的行）。原实现恒返回 0 —— 那个 0 在建表之前
 * 是**正确答案**，建表之后就变成一个等着变错的硬编码，所以接线没等到 13.5。
 */
public interface PendingPublishScheduleCounter {

    /**
     * 数该作者名下**尚未到点发布**的排期条数。
     *
     * @param authorUserId 作者用户 id（虚拟账号或运营真实账号）
     * @return 条数；0 表示没有
     */
    long countPendingFor(long authorUserId);
}
