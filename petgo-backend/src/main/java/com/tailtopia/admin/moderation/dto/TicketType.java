package com.tailtopia.admin.moderation.dto;

/**
 * 统一工单的三个业务类别（Story 3.1 AC3）。
 *
 * <p>⚠️ <b>只有三类，不保留「评论举报」</b> —— 该能力至今未上线，留一个永远没数据的筛选项
 * 只会让运营以为自己漏看了什么。
 *
 * <p>三个类别落在<b>四张表</b>上：账号标识字段那一类由名称审核与头像审核两张并列同构的表拼成。
 */
public enum TicketType {

    /** 内容举报 —— 源表 {@code content_reports}，**按帖聚合**（一条帖子一条工单）。 */
    CONTENT_REPORT,

    /** 用户举报 —— 源表 {@code account_reports}（本版本新增，一个被举报账号一条工单）。 */
    ACCOUNT_REPORT,

    /** 账号标识字段审核 —— 源表 {@code name_moderation_records} + {@code avatar_reviews}。 */
    ACCOUNT_IDENTITY,

    /**
     * 内容送审 —— 源表 {@code manual_review_queue}（2026-08-19 并入混排列表）。
     *
     * <p>与「内容举报」的区别：**没有举报人**。它是机器审核判定「拿不准」的内容，
     * 在发布事务里被挂起等人工放行；举报是内容已经公开、事后有人报。
     *
     * <p>⚠️ 该分支受「人工审核」总闸控制（默认**关**＝机器判完直接发布、此队列恒空）。
     * 那个开关**不影响本列表其余三类**，它管的是内容发布链路要不要走这道关。
     */
    CONTENT_SUBMISSION
}
