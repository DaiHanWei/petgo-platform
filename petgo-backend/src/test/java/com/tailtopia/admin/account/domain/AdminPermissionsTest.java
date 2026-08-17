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
 * L0（Story 9.1 · AB-1.1-29）：后台权限码全集完整性 + 每码有双语 {@code perm.*} 标签。
 * 纯常量 + 类路径资源读取，无 Spring / DB。
 */
class AdminPermissionsTest {

    @Test
    void allContainsV11NewCodesWithoutDuplicates() {
        assertThat(AdminPermissions.ALL).contains(
                AdminPermissions.ORDER_VIEW,
                AdminPermissions.ORDER_EXPORT,
                AdminPermissions.VIRTUAL_ACCOUNT_MANAGE,
                AdminPermissions.CONFIG_VIEW,
                AdminPermissions.CONFIG_EDIT);
        // 退款三码在 Epic 4 已存在，仍应在册。
        assertThat(AdminPermissions.ALL).contains(
                AdminPermissions.REFUND_SUBMIT,
                AdminPermissions.REFUND_APPROVE,
                AdminPermissions.REFUND_PAYOUT,
                AdminPermissions.SUPPORT_HANDLE);
        // 无重复码。
        assertThat(new HashSet<>(AdminPermissions.ALL)).hasSameSizeAs(AdminPermissions.ALL);
    }

    @Test
    void isValidRecognizesNewCodesAndRejectsUnknown() {
        assertThat(AdminPermissions.isValid(AdminPermissions.CONFIG_EDIT)).isTrue();
        assertThat(AdminPermissions.isValid(AdminPermissions.ORDER_EXPORT)).isTrue();
        assertThat(AdminPermissions.isValid("config.nuke")).isFalse();
        assertThat(AdminPermissions.isValid("")).isFalse();
        // bug 20260731-440：三个从未有落点的死码已摘除，勾选页不再出现。
        assertThat(AdminPermissions.isValid("content.export")).isFalse();
        assertThat(AdminPermissions.isValid("content.view_reporters")).isFalse();
        assertThat(AdminPermissions.isValid("consult.edit_sessions")).isFalse();
    }

    @Test
    void everyPermissionHasBilingualLabel() throws Exception {
        Properties zh = load("/i18n/messages_zh_CN.properties");
        Properties en = load("/i18n/messages_en.properties");
        for (String code : AdminPermissions.ALL) {
            String key = "perm." + code;
            assertThat(zh.getProperty(key)).as("zh 缺权限标签 " + key).isNotBlank();
            assertThat(en.getProperty(key)).as("en 缺权限标签 " + key).isNotBlank();
        }
    }

    private Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("缺少资源 " + path).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void listStableSize() {
        // 23 既有 + 7（9.1）+ 2（9.5）+ 2（9.6 payment/risk）+ 10（后续批：审核/评论/名称头像等）
        // = 44 + 1（content.manual_review，stag 拣回）= 45
        // − 3（bug 20260731-440 摘除无落点死码 content.export/content.view_reporters/consult.edit_sessions）= 42
        // + 1（bug 20260728-389 后台赠送 PawCoin user.grant_pawcoin）= 43
        // + 4（V1.4.0 Story 1.3 电商模块 10：shop.product_view/cost_view/product_edit/cost_edit）= 47
        // + 2（V1.4.0 Story 1.4 库存管理 AB-10C：shop.inventory_view/inventory_edit）= 49
        // + 3（V1.4.0 Story 4.2/4.3 模块 11 订单履约：
        //      shop.order_view / shop.order_fulfill / shop.order_phone_search）= 52
        // + 1（V1.4.0 Story 8.4 模块 13 经营数据：shop.finance_view，毛利与对账单独权限位）= 53。
        List<String> all = AdminPermissions.ALL;
        assertThat(all).hasSize(53);
    }
}
