package com.tailtopia.admin.audit.service;

/**
 * 审计动作类型常量（Story 1.3）。落库 {@code action_type}（varchar UPPER_SNAKE，**过去式**）。
 *
 * <p>本故事仅 {@link #EMERGENCY_LOGIN_SUCCEEDED} 有调用方；其余为 Epic 2~6 各业务写操作预留——
 * <b>仅集中定义常量，调用在对应业务故事内接入</b>（统一经 {@code AdminAuditService.record(...)}，禁自拼）。
 */
public final class AuditActions {

    /** 紧急账密（formLogin）登录成功（Story 1.3，AC7）。 */
    public static final String EMERGENCY_LOGIN_SUCCEEDED = "EMERGENCY_LOGIN_SUCCEEDED";

    // ===== Epic 2~6 预留（仅定义，调用在各业务故事）=====
    /** 创建后台/兽医账号。 */
    public static final String ACCOUNT_CREATED = "ACCOUNT_CREATED";
    /** 停用后台账号（Story 1.5）。 */
    public static final String ACCOUNT_DEACTIVATED = "ACCOUNT_DEACTIVATED";
    /** 重新激活后台账号（Story 1.5）。 */
    public static final String ACCOUNT_REACTIVATED = "ACCOUNT_REACTIVATED";
    /** 授予模块权限。 */
    public static final String PERMISSION_GRANTED = "PERMISSION_GRANTED";
    /** 撤销模块权限（Story 1.5）。 */
    public static final String PERMISSION_REVOKED = "PERMISSION_REVOKED";
    /** 创建兽医账号（Story 2.3）。 */
    public static final String VET_CREATED = "VET_CREATED";
    /** 编辑兽医账号资料（Story 2.4）。 */
    public static final String VET_UPDATED = "VET_UPDATED";
    /** 重置兽医密码（Story 2.4）。 */
    public static final String VET_PASSWORD_RESET = "VET_PASSWORD_RESET";
    /** 运营直录兽医资质（Story 2.7）。 */
    public static final String VET_QUALIFICATION_RECORDED = "VET_QUALIFICATION_RECORDED";
    /** 审核通过兽医资质（Story 2.7）。 */
    public static final String VET_QUALIFICATION_APPROVED = "VET_QUALIFICATION_APPROVED";
    /** 驳回兽医资质（Story 2.7）。 */
    public static final String VET_QUALIFICATION_REJECTED = "VET_QUALIFICATION_REJECTED";
    /** 兽医资质续期（Story 2.7）。 */
    public static final String VET_QUALIFICATION_RENEWED = "VET_QUALIFICATION_RENEWED";
    /** 兽医被封禁。 */
    public static final String VET_BANNED = "VET_BANNED";
    /** 兽医解封。 */
    public static final String VET_UNBANNED = "VET_UNBANNED";
    /** 内容被运营主动下架。 */
    public static final String CONTENT_TAKEN_DOWN = "CONTENT_TAKEN_DOWN";
    /** 举报被驳回（Story 4.1）。 */
    public static final String REPORT_DISMISSED = "REPORT_DISMISSED";
    /** 内容被恢复（Story 4.2）。 */
    public static final String CONTENT_RESTORED = "CONTENT_RESTORED";
    /** 用户账号被停用。 */
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";
    /** 用户账号被重新激活（Story 3.2）。 */
    public static final String USER_REACTIVATED = "USER_REACTIVATED";
    /** 用户账号被删除（Story 3.3，D1 注销 / D2 违规）。 */
    public static final String USER_DELETED = "USER_DELETED";
    /** 失败请求标记已跟进（Story 2.9）。 */
    public static final String FAILED_REQUEST_FOLLOWED_UP = "FAILED_REQUEST_FOLLOWED_UP";
    /** 失败请求归档（Story 2.9）。 */
    public static final String FAILED_REQUEST_ARCHIVED = "FAILED_REQUEST_ARCHIVED";
    /** 失败请求加备注（Story 2.9）。 */
    public static final String FAILED_REQUEST_NOTED = "FAILED_REQUEST_NOTED";
    /** 系统设置变更（Story 4.3，如人工审核开关切换）。 */
    public static final String SETTING_CHANGED = "SETTING_CHANGED";
    /** 人工审核通过（Story 4.3）。 */
    public static final String CONTENT_REVIEW_APPROVED = "CONTENT_REVIEW_APPROVED";
    /** 人工审核拒绝（Story 4.3）。 */
    public static final String CONTENT_REVIEW_REJECTED = "CONTENT_REVIEW_REJECTED";
    /** 人工审核超时自动丢弃（Story 4.3）。 */
    public static final String CONTENT_REVIEW_TIMED_OUT = "CONTENT_REVIEW_TIMED_OUT";
    /** 异常工单加内部备注（Story 5.1）。 */
    public static final String ANOMALY_NOTE_ADDED = "ANOMALY_NOTE_ADDED";
    /** 异常工单标记已处理/归档（Story 5.1）。 */
    public static final String ANOMALY_RESOLVED = "ANOMALY_RESOLVED";

    // ===== 退款两段审批（V1.1 Story 4.3，最高危 A-1）=====
    /** 退款请求创建（绑订单/工单）。 */
    public static final String REFUND_REQUEST_CREATED = "REFUND_REQUEST_CREATED";
    /** 客服判定退款需求（APPROVED/REJECTED，Story 4.3 底层原语）。 */
    public static final String REFUND_NEED_SUBMITTED = "REFUND_NEED_SUBMITTED";
    /** 客服批准退款需求（Story 4.4，订单 COMPLETED→REFUNDING，不发通知 AB-5B）。 */
    public static final String REFUND_NEED_APPROVED = "REFUND_NEED_APPROVED";
    /** 客服驳回退款需求（Story 4.4，订单回落 COMPLETED+refund_rejected，发 REFUND_REJECTED 通知 A-2）。 */
    public static final String REFUND_NEED_REJECTED = "REFUND_NEED_REJECTED";
    /** 主管审批通过退款申请。 */
    public static final String REFUND_APPROVED = "REFUND_APPROVED";
    /** 财务记录打款完成。 */
    public static final String REFUND_PAYOUT_RECORDED = "REFUND_PAYOUT_RECORDED";
    /** 职责分离拦截：同一 admin 试图兼任两职（含 SUPER_ADMIN 不豁免，A-1）。 */
    public static final String REFUND_DUTY_VIOLATION_BLOCKED = "REFUND_DUTY_VIOLATION_BLOCKED";

    private AuditActions() {
    }
}
