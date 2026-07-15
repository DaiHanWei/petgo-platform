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

    // 兽医（Epic 2）
    public static final String VET_VIEW = "vet.view";
    public static final String VET_CREATE = "vet.create";
    public static final String VET_BAN = "vet.ban";
    public static final String VET_RESET_PASSWORD = "vet.reset_password";
    /** 资质录入/审核/续期（Story 2.7 新增，附录 B 扩展）。 */
    public static final String VET_QUALIFY = "vet.qualify";

    // 用户账号治理（Epic 3）
    public static final String USER_VIEW = "user.view";
    public static final String USER_DEACTIVATE = "user.deactivate";
    public static final String USER_DELETE = "user.delete";

    // 内容审核（Epic 4）
    public static final String CONTENT_VIEW_REPORTS = "content.view_reports";
    public static final String CONTENT_TAKEDOWN = "content.takedown";
    public static final String CONTENT_RESTORE = "content.restore";
    public static final String CONTENT_PROACTIVE_TAKEDOWN = "content.proactive_takedown";
    /** 人工审核队列：查看 + 通过/拒绝（Story 4.3；开关仍限 SUPER_ADMIN）。 */
    public static final String CONTENT_MANUAL_REVIEW = "content.manual_review";
    /** 内容导出（V1.1 Epic 9）。 */
    public static final String CONTENT_EXPORT = "content.export";
    /** 举报者清单查看（V1.1 Epic 9，运营内部可见，不违 FR-51 对外匿名）。 */
    public static final String CONTENT_VIEW_REPORTERS = "content.view_reporters";

    // 问诊异常与会话（Epic 5）
    public static final String CONSULT_VIEW_ANOMALIES = "consult.view_anomalies";
    public static final String CONSULT_HANDLE = "consult.handle";
    public static final String CONSULT_VIEW_SESSIONS = "consult.view_sessions";

    // 评分（Epic 6）
    public static final String RATING_VIEW = "rating.view";

    // 客服工单（V1.1 Epic 4，Story 4.7）——处理/结案客服工单（FR-52）。
    public static final String SUPPORT_HANDLE = "support.handle";

    // 退款两段审批（V1.1 Epic 4，Story 4.3，三级职责分离 A-1）
    /** 提交退款需求判定（客服）。 */
    public static final String REFUND_SUBMIT = "refund.submit";
    /** 审批退款申请（主管）。 */
    public static final String REFUND_APPROVE = "refund.approve";
    /** 执行退款打款（财务）。 */
    public static final String REFUND_PAYOUT = "refund.payout";

    // 咨询订单 / 收入（V1.1 Epic 9，Story 9-3/9-4）
    /** 兽医·AI 咨询订单只读查看。 */
    public static final String ORDER_VIEW = "order.view";
    /** 订单 / 收入统计导出。 */
    public static final String ORDER_EXPORT = "order.export";

    // 虚拟账号（V1.1 Epic 9，Story 9-8）
    /** 虚拟账号与种子批量上传管理。 */
    public static final String VIRTUAL_ACCOUNT_MANAGE = "virtual_account.manage";

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

    // 后台账号 / 审计（Epic 1）
    public static final String ADMIN_CREATE_ACCOUNT = "admin.create_account";
    public static final String ADMIN_DEACTIVATE = "admin.deactivate";
    public static final String ADMIN_VIEW_LOGS = "admin.view_logs";

    /** 全部合法权限码（UI 勾选项 + 校验白名单），保持模块分组顺序。 */
    public static final List<String> ALL = List.of(
            VET_VIEW, VET_CREATE, VET_BAN, VET_RESET_PASSWORD, VET_QUALIFY,
            USER_VIEW, USER_DEACTIVATE, USER_DELETE,
            CONTENT_VIEW_REPORTS, CONTENT_TAKEDOWN, CONTENT_RESTORE, CONTENT_PROACTIVE_TAKEDOWN,
            CONTENT_MANUAL_REVIEW,
            CONTENT_EXPORT, CONTENT_VIEW_REPORTERS,
            CONSULT_VIEW_ANOMALIES, CONSULT_HANDLE, CONSULT_VIEW_SESSIONS,
            RATING_VIEW,
            SUPPORT_HANDLE,
            REFUND_SUBMIT, REFUND_APPROVE, REFUND_PAYOUT,
            ORDER_VIEW, ORDER_EXPORT,
            VIRTUAL_ACCOUNT_MANAGE,
            CONFIG_VIEW, CONFIG_EDIT,
            SETTLEMENT_VIEW, SETTLEMENT_PAYOUT,
            PAYMENT_VIEW, RISK_VIEW,
            ADMIN_CREATE_ACCOUNT, ADMIN_DEACTIVATE, ADMIN_VIEW_LOGS);

    private AdminPermissions() {
    }

    /** 校验给定权限码是否合法（属于附录 B 全集）。 */
    public static boolean isValid(String code) {
        return ALL.contains(code);
    }
}
