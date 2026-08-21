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
        // + 1（V1.1.4 Story 3.1 统一工单队列 content.view_tickets）= 44
        // + 1（V1.1.4 Story 3.2 工单处置 content.dispose_account）= 45
        // + 2（V1.1.6 Story 11.1 顶置管理 content.pin_view / content.pin_manage）= 47。
        //
        // ⚠️ 这条守的是「新增权限码是件需要被看见的事」：权限码一旦落地即冻结（改名会切断
        //    已授予关系），所以每加一个都应当在这里留一行账，而不是让数字悄悄变大。
        List<String> all = AdminPermissions.ALL;
        assertThat(all).hasSize(47);
    }
}
