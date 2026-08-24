package com.tailtopia.admin.account.domain;

import java.util.List;

/**
 * 后台模块权限码全集（Story 1.5，PRD 附录 B）。全小写点分 {@code <模块>.<动作>}，
 * 直接用作 Spring authority 字符串。UI 勾选项 + 创建/改权限时的合法值校验都取自此处。
 *
 * <p>SUPER_ADMIN 隐式全权（经 {@code hasRole('SUPER_ADMIN') or hasAuthority('...')} 表达式判定，
 * 不往账号注入全集——见 {@code AdminUserDetailsService}）。{@code admin.manage_roles}（动态 RBAC）
 * 属后续版本，V1.0.0 不纳入。
 */
public final class AdminPermissions {
    public record PermissionGroup(String titleCode, List<String> permissionCodes) {
    }

    // 兽医（Epic 2）
    public static final String VET_VIEW = "vet.view";
    public static final String VET_CREATE = "vet.create";
    public static final String VET_EDIT = "vet.edit";
    public static final String VET_BAN = "vet.ban";
    public static final String VET_RESET_PASSWORD = "vet.reset_password";
    /** 资质录入/审核/续期（Story 2.7 新增，附录 B 扩展）。 */
    public static final String VET_QUALIFY = "vet.qualify";
    public static final String VET_QUALIFY_VIEW = "vet.qualify_view";

    // 用户账号治理（Epic 3）
    public static final String USER_VIEW = "user.view";
    public static final String USER_DEACTIVATE = "user.deactivate";
    public static final String USER_DELETE = "user.delete";
    /** 后台赠送 PawCoin（bug 20260728-389：运营向指定账户入账 BONUS）。 */
    public static final String USER_GRANT_PAWCOIN = "user.grant_pawcoin";
    /**
     * 用户标签管理（V1.1.6 Story 11.3 · AB-12A）。查看 / 编辑分两码。
     * ⚠️ 编辑权限**不要下放得比其它模块更宽** —— 该页的分配支持批量，
     * 是"一次影响很多用户"的动作。
     */
    public static final String USER_TAG_VIEW = "user.tag_view";
    public static final String USER_TAG_MANAGE = "user.tag_manage";
    /**
     * 用户手机号查看（V1.1.6 Story 11.4 · AB-11A）。
     *
     * <p>🔴 手机号是 **PII**，因此**不沿用「能看用户详情就能看手机号」** ——
     * 后台 PRD 原写「不新增独立权限项」，2026-08-21 决定改为独立权限。
     */
    public static final String USER_PHONE_VIEW = "user.phone_view";
    /**
     * 召回名单导出（Story 11.4）。
     *
     * <p>🔴 **与查看分开的第二个码**：查看是一次看一个人，导出是把 PII **批量带出系统**，
     * 风险高一档，而且带出之后平台再也管不到它。
     */
    public static final String USER_PHONE_EXPORT = "user.phone_export";

    // 内容审核（Epic 4）
    public static final String CONTENT_VIEW_REPORTS = "content.view_reports";
    public static final String CONTENT_VIEW = "content.view";
    public static final String CONTENT_TAKEDOWN = "content.takedown";
    public static final String CONTENT_RESTORE = "content.restore";
    public static final String CONTENT_PROACTIVE_TAKEDOWN = "content.proactive_takedown";
    /** 人工审核队列：查看 + 通过/拒绝（内容审核 Story 4.3；开关仍限 SUPER_ADMIN）。 */
    public static final String CONTENT_MANUAL_REVIEW = "content.manual_review";
    /**
     * 顶置管理（V1.1.6 Story 11.1 · AB-10A）。查看 / 编辑分两码 ——
     * 顶置直接改首页第一屏，能看不等于能改。
     * ⚠️ 新功能**不做权限回填迁移**：不给存量账号自动授予才是对的，由超管按需勾选。
     */
    public static final String CONTENT_PIN_VIEW = "content.pin_view";
    public static final String CONTENT_PIN_MANAGE = "content.pin_manage";
    /**
     * 内容装饰标签管理（V1.1.6 Story 11.2 · AB-10C）。同样查看 / 编辑分两码 ——
     * 🔴 打标不只是发荣誉，**它同时是个流量动作**（生效中的标签给该内容 ×1.3 曝光加权），
     * 能看不等于能改。
     */
    public static final String CONTENT_TAG_VIEW = "content.tag_view";
    public static final String CONTENT_TAG_MANAGE = "content.tag_manage";
    /**
     * 统一工单队列（V1.1.4 Story 3.1，AB-3D）：三类工单一个列表。
     *
     * <p>与 {@link #CONTENT_VIEW_REPORTS}（旧的举报队列）分开一个码，是因为统一视图里还有
     * <b>账号举报</b>与<b>账号标识字段审核</b>两类 —— 权限粒度跟着能看见的数据走，
     * 不能靠「反正旧码也能看举报」把两类新数据顺带放出去。
     */
    public static final String CONTENT_VIEW_TICKETS = "content.view_tickets";
    /**
     * 从工单队列执行账号级处置（V1.1.4 Story 3.2）：警告 / 封号 / 判为无需处置。
     *
     * <p>⚠️ <b>封号那一档额外还要 {@link #USER_DEACTIVATE}</b>（端点上是 and 关系）——
     * 停用账号本来就是一项受管能力，不能因为「他能看工单」就顺带把停用权也给了。
     */
    public static final String CONTENT_DISPOSE_ACCOUNT = "content.dispose_account";

    // 问诊异常与会话（Epic 5）
    public static final String CONSULT_VIEW_ANOMALIES = "consult.view_anomalies";
    public static final String CONSULT_HANDLE = "consult.handle";
    public static final String CONSULT_VIEW_SESSIONS = "consult.view_sessions";

    // 评分（Epic 6）
    public static final String RATING_VIEW = "rating.view";

    // 客服工单（V1.1 Epic 4，Story 4.7）——处理/结案客服工单（FR-52）。
    public static final String SUPPORT_HANDLE = "support.handle";
    public static final String SUPPORT_VIEW = "support.view";

    // 退款两段审批（V1.1 Epic 4，Story 4.3，三级职责分离 A-1）
    /** 提交退款需求判定（客服）。 */
    public static final String REFUND_SUBMIT = "refund.submit";
    /** 审批退款申请（主管）。 */
    public static final String REFUND_APPROVE = "refund.approve";
    /** 执行退款打款（财务）。 */
    public static final String REFUND_PAYOUT = "refund.payout";
    public static final String REFUND_VIEW = "refund.view";

    // 咨询订单 / 收入（V1.1 Epic 9，Story 9-3/9-4）
    /** 兽医·AI 咨询订单只读查看。 */
    public static final String ORDER_VIEW = "order.view";
    public static final String ORDER_EDIT = "order.edit";
    /** 订单 / 收入统计导出。 */
    public static final String ORDER_EXPORT = "order.export";

    // 虚拟账号（V1.1 Epic 9，Story 9-8）
    /** 虚拟账号与种子批量上传管理。 */
    public static final String VIRTUAL_ACCOUNT_MANAGE = "virtual_account.manage";
    public static final String VIRTUAL_ACCOUNT_VIEW = "virtual_account.view";

    // 运营发布身份池（V1.1.6 Story 12.1 · AB-3I）
    /**
     * 以**运营真实账号**身份发布内容，以及该身份池的纳入 / 移除。
     *
     * <p>🔴 <b>与 {@link #VIRTUAL_ACCOUNT_MANAGE} 完全解耦，刻意独立</b>：
     * 能管虚拟账号 ≠ 能以真人身份发言。以运营真实账号误发的后果**不可撤回** ——
     * 内容会出现在那个真人的个人主页并推送给他的粉丝，事后删除也已经推送过了。
     *
     * <p>本版本**仅分配给超级管理员**，不下放（OQ-24）。权限码独立存在的意义是
     * 后续如需下放，只改权限配置、不改代码。
     */
    public static final String SEED_PUBLISH_AS_REAL = "seed.publish_as_real";

    // 运营配置（V1.1 Epic 9，Story 9-2/9-6）——定价 / PawCoin / 红色超额阈值等
    /** 配置查看。 */
    public static final String CONFIG_VIEW = "config.view";
    /** 配置编辑（定价 / PawCoin / 阈值）。 */
    public static final String CONFIG_EDIT = "config.edit";

    // 兽医分成月结对账（V1.1 Epic 9，Story 9-5）
    /** 月结对账查看。 */
    public static final String SETTLEMENT_VIEW = "settlement.view";
    /** 月结确认打款 / 归档（财务）。 */
    public static final String SETTLEMENT_PAYOUT = "settlement.payout";

    // 支付记录查询 / 风险观测（V1.1 Epic 9，Story 9-6）
    /** 支付记录通用查询。 */
    public static final String PAYMENT_VIEW = "payment.view";
    /** 红色超额只读监控 + 人工标记（无自动拦截，AB-7A）。 */
    public static final String RISK_VIEW = "risk.view";
    public static final String RISK_EDIT = "risk.edit";

    // 后台账号 / 审计（Epic 1）
    public static final String ADMIN_CREATE_ACCOUNT = "admin.create_account";
    public static final String ADMIN_VIEW_ACCOUNTS = "admin.view_accounts";
    public static final String ADMIN_DEACTIVATE = "admin.deactivate";
    public static final String ADMIN_VIEW_LOGS = "admin.view_logs";

    /** 按查看/编辑分组，供账号页勾选区展示。 */
    public static final List<PermissionGroup> GROUPS = List.of(
            new PermissionGroup("perm.group.view", List.of(
                    CONTENT_VIEW_REPORTS, CONTENT_VIEW_TICKETS, CONTENT_VIEW, CONTENT_PIN_VIEW, CONTENT_TAG_VIEW,
                    USER_VIEW, USER_TAG_VIEW, USER_PHONE_VIEW, USER_PHONE_EXPORT,
                    VET_VIEW, VET_QUALIFY_VIEW, RATING_VIEW,
                    CONSULT_VIEW_ANOMALIES, CONSULT_VIEW_SESSIONS,
                    SUPPORT_VIEW, REFUND_VIEW,
                    CONFIG_VIEW, ORDER_VIEW, ORDER_EXPORT, SETTLEMENT_VIEW, PAYMENT_VIEW, RISK_VIEW,
                    VIRTUAL_ACCOUNT_VIEW,
                    ADMIN_VIEW_ACCOUNTS, ADMIN_VIEW_LOGS)),
            new PermissionGroup("perm.group.edit", List.of(
                    CONTENT_TAKEDOWN, CONTENT_RESTORE, CONTENT_PROACTIVE_TAKEDOWN,
                    CONTENT_MANUAL_REVIEW, CONTENT_DISPOSE_ACCOUNT, CONTENT_PIN_MANAGE, CONTENT_TAG_MANAGE,
                    USER_DEACTIVATE, USER_DELETE, USER_GRANT_PAWCOIN, USER_TAG_MANAGE,
                    VET_CREATE, VET_EDIT, VET_BAN, VET_RESET_PASSWORD, VET_QUALIFY,
                    CONSULT_HANDLE,
                    SUPPORT_HANDLE, REFUND_SUBMIT, REFUND_APPROVE, REFUND_PAYOUT,
                    CONFIG_EDIT, ORDER_EDIT, SETTLEMENT_PAYOUT, RISK_EDIT,
                    VIRTUAL_ACCOUNT_MANAGE, SEED_PUBLISH_AS_REAL,
                    ADMIN_CREATE_ACCOUNT, ADMIN_DEACTIVATE)));

    /** 全部合法权限码（UI 勾选项 + 校验白名单），保持模块分组顺序。 */
    public static final List<String> ALL = GROUPS.stream()
            .flatMap(group -> group.permissionCodes().stream())
            .toList();

    private AdminPermissions() {
    }

    /** 校验给定权限码是否合法（属于附录 B 全集）。 */
    public static boolean isValid(String code) {
        return ALL.contains(code);
    }
}
