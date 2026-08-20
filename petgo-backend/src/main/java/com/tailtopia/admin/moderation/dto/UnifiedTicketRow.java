package com.tailtopia.admin.moderation.dto;

import java.time.Instant;

/**
 * 统一工单队列的一行（Story 3.1，AB-3D）。四个业务类别、六张源表，读时联合成同一形状。
 *
 * <p><b>分数必须拆开展示</b>（AC5）：只看 {@code reportCount} 会把「1 个人报了 27 次」当成众怒；
 * 只看 {@code reporterCount} 会丢掉「同一个人反复纠缠」这个信号；只给 {@code score} 等于让运营
 * 对着黑盒排队。所以四个数一起给，页面上并列显示。
 *
 * @param type              工单类别（四类，见 {@link TicketType}）
 * @param sourceId          源表主键。⚠️ <b>内容举报是 post_id（按帖聚合），不是某一条举报的 id</b> ——
 *                          工单的粒度是「被举报对象」，12 个人举报同一条帖子是一条工单
 * @param subType           细分（账号标识字段：NICKNAME / PET_NAME / USER_AVATAR / PET_AVATAR；
 *                          内容送审：{@code <优先级> · <CONTENT_POST|COMMENT>}）
 * @param targetUserId      被举报/被审核的账号；宠物名与宠物头像取其**主人**
 * @param targetNickname    该账号当前昵称（注销为 null）
 * @param targetDeleted     该账号是否已注销
 * @param status            三态（待处理 / 已处理 / 无需处置）
 * @param reporterCount     举报人数 = 举报过该对象的**不同账号数**（去重）
 * @param reportCount       举报次数 = 明细总行数
 * @param frequentCount     高频举报人数 = 其中对该对象累计举报 <b>≥5 次</b>的账号数
 * @param score             优先级分（见 {@link UnifiedTicketQuery} 的公式说明）
 * @param earliestAt        最早一次举报/送审时刻 —— 同分时按它升序（先报的先处理）
 * @param preview           展示用摘要：内容正文片段 / 送审名称原文 / 送审头像 URL。
 *                          ⚠️ 后两者是 PII 红线字段：<b>可展示，严禁进日志</b>
 * @param actionRef         内容举报专用：该帖任意一条 <b>PENDING</b> 举报单 id，下架端点
 *                          {@code /admin/reports/{id}/takedown} 按它收口（会顺带关掉该帖全部
 *                          PENDING 单）。其余类别与「无待处理单」时为 null
 * @param disposalCount     该账号历史被处置次数（含每一次警告）。Story 3.2 写入前恒为 0
 */
public record UnifiedTicketRow(
        TicketType type,
        long sourceId,
        String subType,
        Long targetUserId,
        String targetNickname,
        boolean targetDeleted,
        TicketStatusBucket status,
        long reporterCount,
        long reportCount,
        long frequentCount,
        long score,
        Instant earliestAt,
        String preview,
        Long actionRef,
        long disposalCount) {

    /** 送审挂起超 24h 未处置 → 模板高亮（原「内容送审队列」页 AC7；并入混排后随行迁移，阈值不变）。 */
    public boolean overdue() {
        return type == TicketType.CONTENT_SUBMISSION
                && status == TicketStatusBucket.PENDING
                && earliestAt != null
                && earliestAt.isBefore(Instant.now().minus(java.time.Duration.ofHours(24)));
    }
}
