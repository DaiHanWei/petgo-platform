package com.tailtopia.admin.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * L0（管理后台岗位角色 · V165）：角色→权限码映射的护栏。纯常量 + 类路径资源，无 Spring / DB。
 *
 * <p>这些断言不是「测试代码写了什么」，而是把 NFR-11 与退款三级职责分离的<b>安全边界</b>钉在编译产物上：
 * 以后有人给运营角色顺手加一个 {@code shop.cost_view}，或把提交与审批放进同一个角色，这里会红。
 */
class AdminRoleTest {

    @Test
    void everyReferencedCodeIsARegisteredPermission() {
        assertThat(AdminRole.allReferencedCodes())
                .as("角色引用了不在 AdminPermissions.ALL 的权限码（改码名后此处会静默失效）")
                .allSatisfy(code -> assertThat(AdminPermissions.isValid(code)).isTrue());
    }

    @Test
    void noRoleRepeatsACode() {
        for (AdminRole role : AdminRole.values()) {
            assertThat(new HashSet<>(role.permissionCodes()))
                    .as(role + " 的权限码列表有重复")
                    .hasSameSizeAs(role.permissionCodes());
        }
    }

    @Test
    void superAdminAndCustomCarryNoTemplateCodes() {
        // 超管靠 hasRole('SUPER_ADMIN') 隐式全权，注入全集反而会在新增权限码时漏同步。
        assertThat(AdminRole.SUPER_ADMIN.permissionCodes()).isEmpty();
        // 自定义按 admin_account_permissions 勾选行授权，模板必须是空的。
        assertThat(AdminRole.CUSTOM.permissionCodes()).isEmpty();
        assertThat(AdminRole.CUSTOM.isTemplated()).isFalse();
        assertThat(AdminRole.SUPER_ADMIN.isTemplated()).isTrue();
    }

    @Test
    void accountTypeIsDerivedFromRole() {
        assertThat(AdminRole.SUPER_ADMIN.accountType()).isEqualTo(AdminAccountType.SUPER_ADMIN);
        for (AdminRole role : AdminRole.values()) {
            if (role != AdminRole.SUPER_ADMIN) {
                assertThat(role.accountType())
                        .as(role + " 不该映射成 SUPER_ADMIN 账号类型")
                        .isEqualTo(AdminAccountType.STAFF);
            }
        }
    }

    /**
     * NFR-11：进货价与经营数据只归财务。给发货专员看订单，不等于让他看到整盘生意的成本与毛利。
     */
    @Test
    void onlyFinanceHoldsCostAndBusinessData() {
        List<String> sensitive = List.of(
                AdminPermissions.SHOP_COST_VIEW, AdminPermissions.SHOP_COST_EDIT,
                AdminPermissions.SHOP_FINANCE_VIEW);
        for (AdminRole role : AdminRole.values()) {
            if (role == AdminRole.FINANCE) {
                assertThat(role.permissionCodes()).containsAll(sensitive);
            } else {
                assertThat(role.permissionCodes())
                        .as(role + " 不该默认持有进货价 / 经营数据权限（NFR-11）")
                        .doesNotContainAnyElementsOf(sensitive);
            }
        }
    }

    /**
     * NFR-11：按收件人电话反查全站订单能把「查单」变成「查人」，只给客服。
     * 发货专员即使拿到 {@code shop.order_view}，也不该顺带拿到这条。
     */
    @Test
    void onlySupportCanSearchOrdersByPhone() {
        for (AdminRole role : AdminRole.values()) {
            boolean has = role.permissionCodes().contains(AdminPermissions.SHOP_ORDER_PHONE_SEARCH);
            assertThat(has)
                    .as(role + " 的电话反查订单权限归属不符合预期（应仅客服持有）")
                    .isEqualTo(role == AdminRole.SUPPORT);
        }
        assertThat(AdminRole.FULFILLMENT.permissionCodes())
                .as("发货专员不该能按收件人电话捞人")
                .doesNotContain(AdminPermissions.SHOP_ORDER_PHONE_SEARCH);
    }

    /**
     * 退款三级职责分离（Story 4.3 · A-1）：提交（客服）/ 审批（主管）/ 打款（财务）。
     * 任何单一角色都不得同时持有其中两级，否则一个人就能把退款从头走到尾。
     */
    @Test
    void refundThreeStageSeparationHolds() {
        for (AdminRole role : AdminRole.values()) {
            List<String> held = List.of(
                    AdminPermissions.REFUND_SUBMIT,
                    AdminPermissions.REFUND_APPROVE,
                    AdminPermissions.REFUND_PAYOUT).stream()
                    .filter(role.permissionCodes()::contains).toList();
            assertThat(held)
                    .as(role + " 同时持有退款流程的多级权限，职责分离被打破：" + held)
                    .hasSizeLessThanOrEqualTo(1);
        }
        assertThat(AdminRole.SUPPORT.permissionCodes()).contains(AdminPermissions.REFUND_SUBMIT);
        assertThat(AdminRole.OPS_MANAGER.permissionCodes()).contains(AdminPermissions.REFUND_APPROVE);
        assertThat(AdminRole.FINANCE.permissionCodes()).contains(AdminPermissions.REFUND_PAYOUT);
    }

    /**
     * 授权根与不可逆动作不属于任何岗位模板：注销级联删除（D1/D2）与后台账号增删权，
     * 只能由超管或显式勾选的自定义角色持有。否则「加个运营」会顺手加出一个能删用户、能建号的人。
     */
    @Test
    void noJobRoleHoldsAuthorizationRootOrIrreversibleActions() {
        List<String> reserved = List.of(
                AdminPermissions.USER_DELETE,
                AdminPermissions.ADMIN_CREATE_ACCOUNT,
                AdminPermissions.ADMIN_DEACTIVATE);
        for (AdminRole role : AdminRole.values()) {
            assertThat(role.permissionCodes())
                    .as(role + " 不该默认持有注销删除 / 后台账号增删权")
                    .doesNotContainAnyElementsOf(reserved);
        }
    }

    /** 发货专员刻意收窄在电商模块：不该出现任何非 shop 前缀的权限。 */
    @Test
    void fulfilmentIsScopedToShopModulesOnly() {
        assertThat(AdminRole.FULFILLMENT.permissionCodes())
                .as("发货专员越出电商模块")
                .allSatisfy(code -> assertThat(code).startsWith("shop."));
        assertThat(AdminRole.FULFILLMENT.permissionCodes()).contains(
                AdminPermissions.SHOP_ORDER_VIEW, AdminPermissions.SHOP_ORDER_FULFILL);
    }

    /** 每个角色都要有三语的展示名与职责说明，否则账号页会把 {@code role.OPS_MANAGER} 这种键名裸露出来。 */
    @Test
    void everyRoleHasLabelsInAllLocales() throws Exception {
        for (String locale : List.of("zh_CN", "en", "id")) {
            Properties p = new Properties();
            try (InputStream in = getClass()
                    .getResourceAsStream("/i18n/messages_" + locale + ".properties")) {
                assertThat(in).isNotNull();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (AdminRole role : AdminRole.values()) {
                assertThat(p.getProperty(role.titleCode()))
                        .as(locale + " 缺角色名 " + role.titleCode()).isNotBlank();
                assertThat(p.getProperty(role.descriptionCode()))
                        .as(locale + " 缺角色说明 " + role.descriptionCode()).isNotBlank();
            }
        }
    }

    /** 下拉里必须列全角色，否则会出现「库里有这个角色但页面选不到」的死角。 */
    @Test
    void selectableCoversEveryRole() {
        assertThat(AdminRole.selectable()).containsExactly(AdminRole.values());
    }
}
