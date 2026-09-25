package com.tailtopia.admin.roles;

import com.tailtopia.admin.account.domain.AdminRole;
import java.util.List;
import java.util.Map;

/**
 * V1.3.0 Story 1.4「迁移前真相」快照：四个岗位在迁入 {@code admin_roles} 表<b>之前</b>枚举里的权限码清单，
 * 逐条抄自 AdminRole.java（与 seed 迁移 V20260909_1141 的数组同源）。<b>不要从枚举读</b>——枚举已清空。
 *
 * <p>用途：① L1 {@code AdminRoleSeedParityTest} 断言表内码集合与本清单逐位相等（本 story 的合同）；
 * ② L0 {@code AdminRoleTest} 的 NFR-11 / 退款三级分离等安全边界继续钉在这份清单上；
 * ③ 各 L0 单测 mock 角色表时的数据源。
 */
public final class AdminRoleSeedSnapshot {

    public static final List<String> OPERATIONS = List.of(
            "content.view_reports",
            "content.view",
            "content.takedown",
            "content.restore",
            "content.proactive_takedown",
            "content.manual_review",
            "user.view",
            "vet.view",
            "vet.qualify_view",
            "rating.view",
            "consult.view_anomalies",
            "consult.view_sessions",
            "config.view",
            "order.view",
            "virtual_account.view",
            "virtual_account.manage",
            "shop.product_view",
            "shop.inventory_view",
            "shop.order_view");

    public static final List<String> FULFILLMENT = List.of(
            "shop.product_view",
            "shop.inventory_view",
            "shop.inventory_edit",
            "shop.order_view",
            "shop.order_fulfill");

    public static final List<String> SUPPORT = List.of(
            "user.view",
            "content.view",
            "content.view_reports",
            "vet.view",
            "rating.view",
            "consult.view_anomalies",
            "consult.handle",
            "consult.view_sessions",
            "support.view",
            "support.handle",
            "refund.view",
            "refund.submit",
            "order.view",
            "shop.order_view",
            "shop.order_phone_search");

    public static final List<String> FINANCE = List.of(
            "config.view",
            "order.view",
            "order.export",
            "settlement.view",
            "settlement.payout",
            "payment.view",
            "risk.view",
            "refund.view",
            "refund.payout",
            "shop.order_view",
            "shop.inventory_view",
            "shop.product_view",
            "shop.cost_view",
            "shop.cost_edit",
            "shop.finance_view");

    /** 四个已迁移岗位 → 清单。 */
    public static final Map<AdminRole, List<String>> MIGRATED = Map.of(
            AdminRole.OPERATIONS, OPERATIONS,
            AdminRole.FULFILLMENT, FULFILLMENT,
            AdminRole.SUPPORT, SUPPORT,
            AdminRole.FINANCE, FINANCE);

    /** 「迁移前后应完全一致」的生效权限：已迁移岗位读快照，其余（SUPER_ADMIN / OPS_MANAGER / CUSTOM）读枚举。 */
    public static List<String> effective(AdminRole role) {
        return MIGRATED.getOrDefault(role, role.permissionCodes());
    }

    /** 测试里给已迁移岗位分配的稳定 role_id（与真库无关，仅 mock 用）。 */
    public static long mockRoleId(AdminRole role) {
        return role.ordinal() + 100L;
    }

    private AdminRoleSeedSnapshot() {
    }
}
