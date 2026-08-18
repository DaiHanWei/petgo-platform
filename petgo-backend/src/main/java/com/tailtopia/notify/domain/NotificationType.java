package com.tailtopia.notify.domain;

/**
 * 推送/通知类型（Story 6.1，落库 UPPER_SNAKE）。与 FR-38 深链七类目标 + 兽医端新请求对齐。
 *
 * <ul>
 *   <li>{@link #VET_REPLY} 兽医回复 → 问诊会话（6.2）。</li>
 *   <li>{@link #CONSULT_CLOSED} 问诊结束 → 评分（6.2/5.6）。</li>
 *   <li>{@link #CONTENT_LIKED} 被赞 → 内容详情（6.3）。</li>
 *   <li>{@link #CONTENT_COMMENTED} 被评 → 内容详情定位评论区（6.3）。</li>
 *   <li>{@link #NEW_CONSULT_REQUEST} 兽医端新请求 → 工作台（6.2）。</li>
 *   <li>{@link #PET_BIRTHDAY} 宠物生日 → 「+发布」预选成长日历（6.7，FR-40）。</li>
 *   <li>{@link #COMPANION_ANNIVERSARY} 陪伴纪念日 → 成长档案 Tab（6.7，FR-41）。</li>
 *   <li>{@link #MILESTONE_NODE} L级里程碑节点 → 成长档案 Tab→里程碑列表页（壳）（6.7，FR-42）。</li>
 *   <li>{@link #CONTENT_REMOVED} 内容因违规被运营下架 → 通知作者（3.7 AC3，无深链/无申诉入口，内容已 404）。</li>
 * </ul>
 *
 * <p>🔄 PRD V1.0.0 修订（Fx · 2026-06-08，决策 F2/F5）：新增后三类定时系统推送目标。
 * 本 Story 仅建枚举与深链路由地基；定时投递在 6.7。
 */
public enum NotificationType {
    VET_REPLY,
    CONSULT_CLOSED,
    CONTENT_LIKED,
    CONTENT_COMMENTED,
    NEW_CONSULT_REQUEST,
    PET_BIRTHDAY,
    COMPANION_ANNIVERSARY,
    MILESTONE_NODE,
    CONTENT_REMOVED,
    /** 举报已处理 → 通知举报人的统一模糊闭环（Story 4.1，AB-3A；不透露处置结果/内容/作者）。 */
    REPORT_REVIEWED,
    /** 人工审核通过 → 通知作者「已通过」（Story 4.3，仅开关激活后产生）。 */
    CONTENT_REVIEW_APPROVED,
    /** 人工审核未通过/超时丢弃 → 通知作者「未通过」（Story 4.3，仅开关激活后产生）。 */
    CONTENT_REVIEW_REJECTED,
    /**
     * 昵称/宠物名违规重置 → 通知本人（内容审核 story 4，D-CM6/CM3）。单一类型，{@code targetRef} 区分：
     * "NICKNAME" → 跳「设置昵称」页；否则为宠物 cardToken → 跳该宠物改名页。
     * 显示串由 App 按 type 本地化（arb 文案归 cm-7），后端只发结构化通知。
     */
    NAME_RESET,
    /**
     * 用户/宠物头像违规重置 → 通知本人（内容审核 story 5，D-CM6/§5.5）。单一类型，{@code targetRef} 区分：
     * "USER_AVATAR" → 跳我的页换头像入口；否则为宠物 cardToken → 跳该宠物档案编辑页换头像。
     * 显示串由 App 按 type 本地化（arb 文案归 cm-7），后端只发结构化通知。
     */
    AVATAR_RESET,
    /**
     * 帖子人工审核队列超过 3 天未处理、自动超时丢弃 → 通知作者（内容审核 story 7，§8.8）。
     * 与 {@link #CONTENT_REVIEW_REJECTED}（人工拒绝，§8.7）文案不同故拆型；App 只能按 type 本地化。
     * {@code targetRef=null}（内容已丢弃、无深链，提示重发）。
     */
    CONTENT_REVIEW_TIMED_OUT,

    // ===== V1.1 Epic 4 退款/工单/身份（extend_notification_types_v11，V72 一次加全，避免二次迁移）=====
    /** 退款申请未通过 → 通知发起用户（Story 4.4，客服驳回退款需求；不含金额/账号 PII）。本 story 唯一新发的类型。 */
    REFUND_REJECTED,
    /** 工单已结案 → 通知用户（Story 4.7，枚举先行占位，本 story 不发）。 */
    TICKET_RESOLVED,
    /** CSAT 满意度问卷 → 通知用户（Story 4.7，枚举先行占位，本 story 不发）。 */
    CSAT_SURVEY,
    /** 身份信息需修改 → 通知用户（Epic 9 身份核验，枚举先行占位，本 story 不发）。 */
    IDENTITY_REQUIRE_MODIFY
}
