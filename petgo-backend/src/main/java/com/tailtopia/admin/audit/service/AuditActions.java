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
    /** 评论被运营主动下架（内容审核 story 3，FR-55A，objectType=COMMENT，含原因）。 */
    public static final String COMMENT_TAKEN_DOWN = "COMMENT_TAKEN_DOWN";
    /** 下架评论被恢复（内容审核 story 3，FR-55A，objectType=COMMENT）。 */
    public static final String COMMENT_RESTORED = "COMMENT_RESTORED";
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
    /** 客服工单结案（Story 4.7，「已联系+已解决」→ RESOLVED + 发结案/CSAT 通知）。 */
    public static final String TICKET_RESOLVED = "TICKET_RESOLVED";
    /** 客服为工单补挂关联订单（AB-5B 退款判定前置，bug 20260728-384）。 */
    public static final String TICKET_ORDER_LINKED = "TICKET_ORDER_LINKED";
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
    /** 主管驳回退款申请（Story 4.6，第二段审批，订单回落 COMPLETED+refund_rejected + 通知用户）。 */
    public static final String REFUND_APPROVAL_REJECTED = "REFUND_APPROVAL_REJECTED";
    /** 财务记录打款完成。 */
    public static final String REFUND_PAYOUT_RECORDED = "REFUND_PAYOUT_RECORDED";
    /** 职责分离拦截：同一 admin 试图兼任两职（含 SUPER_ADMIN 不豁免，A-1）。 */
    public static final String REFUND_DUTY_VIOLATION_BLOCKED = "REFUND_DUTY_VIOLATION_BLOCKED";

    // ===== 用户账号治理补充 =====
    /** 后台赠送 PawCoin（bug 20260728-389；summary 含数量/原因/幂等键，不落 PII）。 */
    public static final String PAWCOIN_GRANTED = "PAWCOIN_GRANTED";

    // ===== 内容审核补充规范 story 8（后台审核增强）=====
    /** 调整人工审核队列项优先级（story 8，§5.1，含旧→新优先级）。 */
    public static final String REVIEW_PRIORITY_CHANGED = "REVIEW_PRIORITY_CHANGED";
    /** 名称违规重置为系统默认编码名（story 4 处置，story 8 后台入口触发；summary 含判定依据/备注，无名称原文）。 */
    public static final String NAME_RESET = "NAME_RESET";
    /** 头像违规重置为平台默认头像（story 5 处置，story 8 后台入口触发；summary 含判定依据/备注，无图片 URL）。 */
    public static final String AVATAR_RESET = "AVATAR_RESET";

    // ===== V1.1.4 Story 3.2 账号级处置（社区管控）=====
    /** 账号警告（summary 只带工单号，绝不带内容原文/举报人）。 */
    public static final String ACCOUNT_WARNED = "ACCOUNT_WARNED";
    /** 账号停用（社区处置路径；与用户管理页的 USER_DEACTIVATED 分开，两条路径的副作用不同）。 */
    public static final String ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED";
    /** 账号举报工单判为无需处置（数据层仍是 DISMISSED，改的只有展示层文案）。 */
    public static final String ACCOUNT_REPORT_DISMISSED = "ACCOUNT_REPORT_DISMISSED";

    // ===== 精选自营电商（V1.4.0 Story 1.3，模块 10）=====
    /** 创建商品（AB-10A）。 */
    public static final String SHOP_PRODUCT_CREATED = "SHOP_PRODUCT_CREATED";
    /** 编辑商品（AB-10A）。 */
    public static final String SHOP_PRODUCT_UPDATED = "SHOP_PRODUCT_UPDATED";
    /** 新建或更新 SKU（AB-10B）。 */
    public static final String SHOP_SKU_UPSERTED = "SHOP_SKU_UPSERTED";
    /** 🔒 更新进货价——详情【绝不写数值】，只记发生过（商业敏感，NFR-11）。 */
    public static final String SHOP_PRODUCT_COST_UPDATED = "SHOP_PRODUCT_COST_UPDATED";

    // ===== 库存管理与采购入库（V1.4.0 Story 1.4，AB-10C）=====
    /** 🔒 采购/退货入库登记——详情记数量与前后值，【绝不写进货单价数值】（同 SHOP_PRODUCT_COST_UPDATED 的处置）。 */
    public static final String SHOP_INVENTORY_RECEIPT_CREATED = "SHOP_INVENTORY_RECEIPT_CREATED";
    /** 报损——详情记数量、原因与前后值。 */
    public static final String SHOP_INVENTORY_DAMAGED = "SHOP_INVENTORY_DAMAGED";
    /** 盘点调整——详情记盘点值、原因与前后值。 */
    public static final String SHOP_INVENTORY_STOCKTAKED = "SHOP_INVENTORY_STOCKTAKED";

    // ===== 上下架与精选排序（V1.4.0 Story 1.5，AB-10D）=====
    /** 商品上架（详情记上架后的在售 SKU 总数与上限，便于回溯超限争议）。 */
    public static final String SHOP_PRODUCT_LISTED = "SHOP_PRODUCT_LISTED";
    /** 商品下架。🔴 只改可见性，不触发任何库存或订单动作（SPEC-7 口径）。 */
    public static final String SHOP_PRODUCT_DELISTED = "SHOP_PRODUCT_DELISTED";

    private AuditActions() {
    }
}
