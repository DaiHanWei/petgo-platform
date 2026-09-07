package com.tailtopia.profile.visitor;

/**
 * 访客视图的统计条（V1.1.6 Story 2.1）——<b>只有三列</b>。
 *
 * <p>🔴 <b>刻意没有 {@code healthRecordCount}。</b>
 * 作者态的 {@code ArchiveStatsResponse} 有第 5 个字段「结构化健康记录条数」。
 * 本 story 要求<b>复用作者态的统计实现（不自行重算）</b>，但那<b>不等于原样透传那个对象</b> ——
 * 复用的是<b>计算</b>，下发的只有这三个数。
 *
 * <p>为什么条数也不能给：它虽然不是记录内容，却足以推断出
 * 「这只宠物有没有健康问题记录」。而 PRD §2.9 的可见范围表里，健康记录整块都是 ❌。
 *
 * <p>⚠️ 「问诊<b>次数</b>」与「问诊<b>记录</b>」是两件事：次数是计数、可下发（PRD 2026-08-06 产品确认保留）；
 * 记录本身不可。别因为这里有 {@code consultCount} 就以为问诊内容也能给。
 *
 * <p>🔴 <b>字段名必须与作者态那份保持一致</b>（{@code happyMomentCount}）——
 * 客户端两边用的是<b>同一个</b>模型类去解析，名字对不上就会被解析时的兜底默认值
 * <b>静默变成 0</b>：页面照常渲染、不报任何错，只是统计条永远显示 0。
 * 2026-08-18 的 L2 视觉验收就是这么发现的（当时这里叫 {@code diaryCount}）。
 * 产品口径叫「Diary」是展示文案的事，由客户端的多语言文案决定，不该反过来改线上契约的字段名。
 *
 * @param happyMomentCount   Diary 条数（<b>含</b>作者关闭同步的私密条目，与访客可见的时间线一致）
 * @param consultCount       问诊次数（只是计数，不含任何问诊内容）
 * @param milestoneCompleted 已完成里程碑数
 * @param milestoneTotal     里程碑总数（按物种：猫 31 / 狗 31 / 通用 16）
 */
public record VisitorStats(
        long happyMomentCount,
        long consultCount,
        long milestoneCompleted,
        int milestoneTotal) {
}
