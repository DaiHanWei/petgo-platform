package com.tailtopia.content.rank;

import java.time.Instant;

/**
 * 打分与配比的输入项（V1.1.6 Story 16.2）。
 *
 * <p>🔴 <b>赞数与评论数是「已经批量取好的」值</b>（AC5）：引擎不持有任何 repository，
 * 所以「逐条 COUNT」在结构上不可能发生 —— 这不是靠自觉，是靠拿不到查询入口。
 *
 * <p>⚠️ 量级和 Story 15.1 的互动积分榜完全不同：那是「全平台几百条内容出一页榜」，
 * 逐条子查询没问题；而推荐序是<b>每个用户每次下拉都要对候选池算 100 条序列</b>，
 * 同样形状的查询在这里就是 N+1。批量取数的落点在 16.3。
 *
 * @param id        内容 id
 * @param authorId  作者 id（防扎堆按<b>作者</b>维度，不是「被打标内容」维度）
 * @param attribute 属性；{@code null} 的候选须由调用方剔除
 * @param species   物种归属；{@code null} = 推不出来（引擎按 GENERAL 处理，见 {@link SpeciesBucket#of}）
 * @param createdAt 发布时间（算新鲜度）
 * @param likes     点赞数
 * @param comments  评论数
 */
public record RankCandidate(
        long id,
        long authorId,
        FeedAttribute attribute,
        String species,
        Instant createdAt,
        long likes,
        long comments) {
}
