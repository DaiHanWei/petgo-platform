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

    // 问诊异常与会话（Epic 5）
    public static final String CONSULT_VIEW_ANOMALIES = "consult.view_anomalies";
    public static final String CONSULT_HANDLE = "consult.handle";
    public static final String CONSULT_VIEW_SESSIONS = "consult.view_sessions";

    // 评分（Epic 6）
    public static final String RATING_VIEW = "rating.view";

    // 后台账号 / 审计（Epic 1）
    public static final String ADMIN_CREATE_ACCOUNT = "admin.create_account";
    public static final String ADMIN_DEACTIVATE = "admin.deactivate";
    public static final String ADMIN_VIEW_LOGS = "admin.view_logs";

    /** 全部合法权限码（UI 勾选项 + 校验白名单），保持模块分组顺序。 */
    public static final List<String> ALL = List.of(
            VET_VIEW, VET_CREATE, VET_BAN, VET_RESET_PASSWORD, VET_QUALIFY,
            USER_VIEW, USER_DEACTIVATE, USER_DELETE,
            CONTENT_VIEW_REPORTS, CONTENT_TAKEDOWN, CONTENT_RESTORE, CONTENT_PROACTIVE_TAKEDOWN,
            CONSULT_VIEW_ANOMALIES, CONSULT_HANDLE, CONSULT_VIEW_SESSIONS,
            RATING_VIEW,
            ADMIN_CREATE_ACCOUNT, ADMIN_DEACTIVATE, ADMIN_VIEW_LOGS);

    private AdminPermissions() {
    }

    /** 校验给定权限码是否合法（属于附录 B 全集）。 */
    public static boolean isValid(String code) {
        return ALL.contains(code);
    }
}
