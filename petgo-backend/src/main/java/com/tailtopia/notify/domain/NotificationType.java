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
    IDENTITY_REQUIRE_MODIFY,

    // ===== V1.1.4 Story 3.2 账号级处置（V104 已把这两值加进 ck_notifications_type）=====
    /**
     * 账号收到一次警告 → 通知本人（Story 3.2，FR-58）。
     *
     * <p>⚠️ **不告知**是谁举报的、因哪条内容、也**不告知这是第几次**警告 ——
     * 说了就等于把举报人暴露给被举报人，而「第几次」会变成一个可以试探的计数器。
     * 警告**不影响使用**（能登录、能发内容、内容可见性不变），也**不给异议渠道**（与封号不同）。
     * {@code targetRef=null}：没有可跳的目标，跳社区规范页是 V1.1.6 以后的事。
     */
    ACCOUNT_WARNED,

    /**
     * 账号被停用 → 通知本人（Story 3.2，FR-58）。
     *
     * <p>异议走 **FR-52 通用客服工单**，由客服按个案人工判断 ——
     * **不建立独立的申诉审核工作流或 UI**，客服就是唯一的异议反馈渠道。
     * 印尼语措辞已经法务审核（2026-08-16，C-101）。
     */
    ACCOUNT_SUSPENDED,

    // ===== V1.4.0 精选自营电商（V116 追加共享 CHECK 值，依临时授权）=====
    /**
     * 电商订单已发货 → 深链直跳订单详情（Story 4.2，FR-38）。
     * {@code targetRef} = 订单 {@code publicToken}（🔴 不可枚举，不用自增 id）。
     * 🔒 文案<b>只提「订单已发货」</b>，不带商品名 / 收件人 / 地址 —— 推送会落在锁屏上。
     */
    SHOP_ORDER_SHIPPED,
    /**
     * 电商订单异常已处置 → 深链订单详情（Story 4.4，AB-11D / S-3）。
     * 用于「整单取消并退款」「部分取消」「联系用户后继续」三种处置的告知与致歉。
     * 🔒 文案含处置原因，<b>不含金额明细、不含收件信息</b> —— 金额在订单详情页看。
     */
    SHOP_ORDER_EXCEPTION,
    /**
     * 退货进度更新 → 深链退货进度页（Story 5.3/5.4/5.5，AB-12A/B/C）。
     * 审核通过/驳回、质检通过/不通过、退款完成共用一个类型，正文说明进展。
     * 🔒 正文不含金额明细与收件信息 —— 金额在退货进度页看。
     */
    SHOP_RETURN_UPDATED,
    /**
     * 粮量见底补货提醒 → 深链商品详情（Story 6.3，FR-109）。
     *
     * <p>🔴 <b>复用现有推送通道，不新建提醒引擎</b> —— FR-109 明写这一条。
     * 🔒 文案只说「快吃完了」，<b>不带体重、不带具体克数</b>：体重是 PII 邻近的健康数据，
     * 而推送会落在锁屏上（NFR-5）。
     * 🔴 文案给<b>估算依据而非断言</b>：档案体重不准或用户混喂时会有偏差。
     */
    REPURCHASE_FOOD_LOW,

    /**
     * S/M 级里程碑达成（V1.1.6 Story 6.1 · FR-76 / AD-13）——
     * <b>写通知中心、不发系统推送</b>；点击落点与 {@link #MILESTONE_NODE} 一致（里程碑列表页）。
     *
     * <p>🛡 与 L 级**分成两种类型**而不是复用一种：复用会让 S/M 也走系统推送，
     * 而 S/M 数量多得多，那是打扰。L 级维持现状（推送 + 通知中心两者都有）。
     *
     * <p>⚠️ 通知中心显示的标题/正文由 App 按类型本地化，**后端下发的文案只用于推送**
     * （本类型不推送，故那两段文案实际不出现在任何界面上，仅作留痕）。
     * 具体是哪一条里程碑，由 App 拿 {@code targetRef}（里程碑编码）查它自己那份双语表。
     */
    MILESTONE_SM_NODE
,

    // ===== 留存运营作战手册 · 抓手 1：生命周期推送（V20260821_1646 追加 CHECK 值）=====
    // 铁律：文案永远说「记录你的宠物」，绝不说「回来看看」—— 后者是空话，
    // 前者是用户自己用行为承认过的动机（106/343 发布过内容，发布是唯一的强行为）。
    // 四类共用一套分流：深链落点由 targetRef 携带的 variant 决定
    // （CREATE_PROFILE / RECORD / FEED / REVIEW），沿用 NAME_RESET/AVATAR_RESET 的单类型 + variant 范式。
    /** D1 次日：把「记录宠物」变成第一天的习惯（手册第一武器）。 */
    LIFECYCLE_D1,
    /** D3：仍未发布 → 内容钩子「看看别人家宠物今天做了什么」。 */
    LIFECYCLE_D3,
    /** D7：周回顾「这一周 {petName} 的成长」，留存钩子 + 分享获客。 */
    LIFECYCLE_D7,
    /** 流失召回：{@code last_active_at} 距今 ≥ N 天，深链直达建档 / 发布。每月至多一次。 */
    LIFECYCLE_WINBACK
}
