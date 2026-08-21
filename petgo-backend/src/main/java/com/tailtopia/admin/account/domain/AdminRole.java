package com.tailtopia.admin.account.domain;

import static com.tailtopia.admin.account.domain.AdminPermissions.ADMIN_VIEW_ACCOUNTS;
import static com.tailtopia.admin.account.domain.AdminPermissions.ADMIN_VIEW_LOGS;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONFIG_EDIT;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONFIG_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONSULT_HANDLE;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONSULT_VIEW_ANOMALIES;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONSULT_VIEW_SESSIONS;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONTENT_MANUAL_REVIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONTENT_PROACTIVE_TAKEDOWN;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONTENT_RESTORE;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONTENT_TAKEDOWN;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONTENT_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.CONTENT_VIEW_REPORTS;
import static com.tailtopia.admin.account.domain.AdminPermissions.ORDER_EDIT;
import static com.tailtopia.admin.account.domain.AdminPermissions.ORDER_EXPORT;
import static com.tailtopia.admin.account.domain.AdminPermissions.ORDER_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.PAYMENT_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.RATING_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.REFUND_APPROVE;
import static com.tailtopia.admin.account.domain.AdminPermissions.REFUND_PAYOUT;
import static com.tailtopia.admin.account.domain.AdminPermissions.REFUND_SUBMIT;
import static com.tailtopia.admin.account.domain.AdminPermissions.REFUND_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.RISK_EDIT;
import static com.tailtopia.admin.account.domain.AdminPermissions.RISK_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.SETTLEMENT_PAYOUT;
import static com.tailtopia.admin.account.domain.AdminPermissions.SETTLEMENT_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.SHOP_COST_EDIT;
import static com.tailtopia.admin.account.domain.AdminPermissions.SHOP_COST_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.SHOP_FINANCE_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.SHOP_INVENTORY_EDIT;
import static com.tailtopia.admin.account.domain.AdminPermissions.SHOP_INVENTORY_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.SHOP_ORDER_FULFILL;
import static com.tailtopia.admin.account.domain.AdminPermissions.SHOP_ORDER_PHONE_SEARCH;
import static com.tailtopia.admin.account.domain.AdminPermissions.SHOP_ORDER_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.SHOP_PRODUCT_EDIT;
import static com.tailtopia.admin.account.domain.AdminPermissions.SHOP_PRODUCT_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.SUPPORT_HANDLE;
import static com.tailtopia.admin.account.domain.AdminPermissions.SUPPORT_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.USER_DEACTIVATE;
import static com.tailtopia.admin.account.domain.AdminPermissions.USER_GRANT_PAWCOIN;
import static com.tailtopia.admin.account.domain.AdminPermissions.USER_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.VET_BAN;
import static com.tailtopia.admin.account.domain.AdminPermissions.VET_CREATE;
import static com.tailtopia.admin.account.domain.AdminPermissions.VET_EDIT;
import static com.tailtopia.admin.account.domain.AdminPermissions.VET_QUALIFY;
import static com.tailtopia.admin.account.domain.AdminPermissions.VET_QUALIFY_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.VET_RESET_PASSWORD;
import static com.tailtopia.admin.account.domain.AdminPermissions.VET_VIEW;
import static com.tailtopia.admin.account.domain.AdminPermissions.VIRTUAL_ACCOUNT_MANAGE;
import static com.tailtopia.admin.account.domain.AdminPermissions.VIRTUAL_ACCOUNT_VIEW;

import java.util.List;
import java.util.Set;

/**
 * 后台<b>岗位角色</b>（落库 {@code admin_accounts.role}，varchar + UPPER_SNAKE）。
 *
 * <p>角色是<b>加在既有 {@link AdminPermissions} 权限码之上的一层</b>，不替代它：
 * 门控表达式（{@code @PreAuthorize} / {@code sec:authorize}）一行不改，仍判 {@code hasAuthority('<code>')}；
 * 角色只决定<b>一个账号登录时装载哪些权限码</b>（见 {@code AdminUserDetailsService}）。
 *
 * <p><b>为什么角色→权限码写在代码里而不是建表：</b>权限码本身就是代码常量，两者同源才有编译期一致性——
 * 新模块加权限码时可在同一个 PR 里决定它归哪些岗位，随 git 留痕；建表则会与代码里的码集悄悄漂移。
 * 代价是改角色定义要发版，这对「岗位」这种低频变更是可接受的。需要临时特例时用 {@link #CUSTOM}。
 *
 * <p><b>权限按角色实时解析，不落 {@code admin_account_permissions} 表</b>（{@link #CUSTOM} 除外）：
 * 于是给某角色新增一个模块权限时，该角色的存量账号下次登录即生效，无需逐个重存——消除漂移。
 *
 * <h2>敏感权限的默认归属（NFR-11）</h2>
 * <ul>
 *   <li>{@code shop.cost_*}（进货价）/ {@code shop.finance_view}（毛利与对账）→ 仅 {@link #FINANCE}。
 *       运营与发货岗<b>默认拿不到</b>，包括 {@link #OPS_MANAGER}。</li>
 *   <li>{@code shop.order_phone_search}（按收件人电话反查全站订单，PII）→ 仅 {@link #SUPPORT}——
 *       客服按来电找单是其本职；<b>发货岗没有</b>：给他看单不等于给他按号码捞人。</li>
 *   <li>退款三级职责分离（Story 4.3 A-1）由三个角色分持，任一角色都不同时具备两级：
 *       提交 {@link #SUPPORT} → 审批 {@link #OPS_MANAGER} → 打款 {@link #FINANCE}。</li>
 *   <li>{@code user.delete}（注销级联删除，D1/D2 安全攸关）与 {@code admin.create_account} /
 *       {@code admin.deactivate}（授权根）→ <b>不属于任何岗位角色</b>，仅超管或 {@link #CUSTOM} 显式授予。</li>
 * </ul>
 */
public enum AdminRole {

    /**
     * 超级管理员：隐式全权（经 {@code hasRole('SUPER_ADMIN')} 命中，不注入权限码全集——
     * 新增权限码无需同步，抗遗漏）。上限 5，见 {@code AdminAccountService.SUPER_ADMIN_CAP}。
     */
    SUPER_ADMIN(List.of()),

    /**
     * 运营主管：运营线负责人。运营专员全部 + 兽医档案与资质、用户停用、配置编辑、退款审批、
     * 库存变更、订单履约、审计与账号只读。
     *
     * <p>⚠️ 无 {@code shop.cost_edit}，故「采购入库」（须填进货单价，S-9）做不了，只能做报损/盘点调整。
     */
    OPS_MANAGER(List.of(
            CONTENT_VIEW_REPORTS, CONTENT_VIEW, CONTENT_TAKEDOWN, CONTENT_RESTORE,
            CONTENT_PROACTIVE_TAKEDOWN, CONTENT_MANUAL_REVIEW,
            USER_VIEW, USER_DEACTIVATE, USER_GRANT_PAWCOIN,
            VET_VIEW, VET_CREATE, VET_EDIT, VET_BAN, VET_RESET_PASSWORD,
            VET_QUALIFY, VET_QUALIFY_VIEW, RATING_VIEW,
            CONSULT_VIEW_ANOMALIES, CONSULT_HANDLE, CONSULT_VIEW_SESSIONS,
            SUPPORT_VIEW, SUPPORT_HANDLE,
            REFUND_VIEW, REFUND_APPROVE,
            CONFIG_VIEW, CONFIG_EDIT,
            ORDER_VIEW, ORDER_EDIT, ORDER_EXPORT,
            RISK_VIEW, RISK_EDIT,
            VIRTUAL_ACCOUNT_VIEW, VIRTUAL_ACCOUNT_MANAGE,
            SHOP_PRODUCT_VIEW, SHOP_PRODUCT_EDIT,
            SHOP_INVENTORY_VIEW, SHOP_INVENTORY_EDIT,
            SHOP_ORDER_VIEW, SHOP_ORDER_FULFILL,
            ADMIN_VIEW_ACCOUNTS, ADMIN_VIEW_LOGS)),

    /**
     * 运营专员：内容审核、商品与活动维护、社区与用户只读。
     * 不含任何审批/打款/停用类动作，也不含库存变更。
     */
    OPERATIONS(List.of(
            CONTENT_VIEW_REPORTS, CONTENT_VIEW, CONTENT_TAKEDOWN, CONTENT_RESTORE,
            CONTENT_PROACTIVE_TAKEDOWN, CONTENT_MANUAL_REVIEW,
            USER_VIEW,
            VET_VIEW, VET_QUALIFY_VIEW, RATING_VIEW,
            CONSULT_VIEW_ANOMALIES, CONSULT_VIEW_SESSIONS,
            CONFIG_VIEW, ORDER_VIEW,
            VIRTUAL_ACCOUNT_VIEW, VIRTUAL_ACCOUNT_MANAGE,
            SHOP_PRODUCT_VIEW, SHOP_INVENTORY_VIEW, SHOP_ORDER_VIEW)),

    /**
     * 发货专员：电商履约闭环——看单、发货、标记送达、异常处置、库存进出。
     *
     * <p>刻意收窄到电商模块：无用户/内容/兽医/问诊/财务任何入口，无 {@code shop.order_phone_search}，
     * 无 {@code shop.cost_view}（故商品页不下发进货价字段）。⚠️ 同样因无 {@code shop.cost_edit}，
     * 采购入库需运营主管或财务配合。
     */
    FULFILLMENT(List.of(
            SHOP_PRODUCT_VIEW,
            SHOP_INVENTORY_VIEW, SHOP_INVENTORY_EDIT,
            SHOP_ORDER_VIEW, SHOP_ORDER_FULFILL)),

    /**
     * 客服：工单处理、退款需求提交（三级分离的第一级）、问诊异常跟进、按电话找单。
     * 只读用户与内容，不能停用用户、不能审批或打款。
     */
    SUPPORT(List.of(
            USER_VIEW,
            CONTENT_VIEW, CONTENT_VIEW_REPORTS,
            VET_VIEW, RATING_VIEW,
            CONSULT_VIEW_ANOMALIES, CONSULT_HANDLE, CONSULT_VIEW_SESSIONS,
            SUPPORT_VIEW, SUPPORT_HANDLE,
            REFUND_VIEW, REFUND_SUBMIT,
            ORDER_VIEW,
            SHOP_ORDER_VIEW, SHOP_ORDER_PHONE_SEARCH)),

    /**
     * 财务：兽医月结、支付记录、退款打款（三级分离的第三级）、进货价与经营数据。
     * 商业敏感权限（{@code shop.cost_*} / {@code shop.finance_view}）的<b>唯一</b>默认持有角色。
     */
    FINANCE(List.of(
            CONFIG_VIEW,
            ORDER_VIEW, ORDER_EXPORT,
            SETTLEMENT_VIEW, SETTLEMENT_PAYOUT,
            PAYMENT_VIEW, RISK_VIEW,
            REFUND_VIEW, REFUND_PAYOUT,
            SHOP_ORDER_VIEW, SHOP_INVENTORY_VIEW, SHOP_PRODUCT_VIEW,
            SHOP_COST_VIEW, SHOP_COST_EDIT, SHOP_FINANCE_VIEW)),

    /**
     * 自定义：不套用任何岗位模板，权限逐码勾选、落 {@code admin_account_permissions} 表
     * （Story 1.5 的原有形态）。存量 STAFF 账号迁移到此角色，行为与迁移前完全一致。
     */
    CUSTOM(List.of());

    private final List<String> permissionCodes;

    AdminRole(List<String> permissionCodes) {
        this.permissionCodes = List.copyOf(permissionCodes);
    }

    /**
     * 该角色隐含的权限码（{@link #SUPER_ADMIN} 与 {@link #CUSTOM} 为空——前者隐式全权、
     * 后者按账号勾选行授权）。顺序稳定，供 UI 展示。
     */
    public List<String> permissionCodes() {
        return permissionCodes;
    }

    /** 权限是否由角色模板决定（{@code false} 则读 {@code admin_account_permissions} 勾选行）。 */
    public boolean isTemplated() {
        return this != CUSTOM;
    }

    /** 岗位角色是否对应 {@code account_type=SUPER_ADMIN}（认证层 {@code ROLE_SUPER_ADMIN} 的来源）。 */
    public boolean isSuperAdmin() {
        return this == SUPER_ADMIN;
    }

    /** 由角色推导账号类型——单一下拉即可定型，避免「角色/类型」两个字段被填成互相矛盾的组合。 */
    public AdminAccountType accountType() {
        return isSuperAdmin() ? AdminAccountType.SUPER_ADMIN : AdminAccountType.STAFF;
    }

    /** i18n 键：{@code role.OPS_MANAGER} 等（zh_CN / en / id 三语，见 {@code i18n/messages_*.properties}）。 */
    public String titleCode() {
        return "role." + name();
    }

    /** i18n 键：角色职责一句话说明，创建账号时展示。 */
    public String descriptionCode() {
        return "role." + name() + ".desc";
    }

    /** 可在账号页选择的角色（全部；顺序即下拉顺序，超管在首、自定义在末）。 */
    public static List<AdminRole> selectable() {
        return List.of(values());
    }

    /**
     * 自检用：所有角色引用的权限码都必须属附录 B 全集。
     * 由 L0 测试断言，防止 {@link AdminPermissions} 改码名后此处静默失效。
     */
    public static Set<String> allReferencedCodes() {
        return java.util.Arrays.stream(values())
                .flatMap(r -> r.permissionCodes.stream())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
