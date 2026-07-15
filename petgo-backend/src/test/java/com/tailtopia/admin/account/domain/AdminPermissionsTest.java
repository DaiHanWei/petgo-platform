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
                AdminPermissions.CONTENT_EXPORT,
                AdminPermissions.CONTENT_VIEW_REPORTERS,
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
        // 23 既有 + 7（9.1）+ 2（9.5）+ 2（9.6 payment/risk）= 34（防误删/误加漏更新测试）。
        List<String> all = AdminPermissions.ALL;
        assertThat(all).hasSize(34);
    }
}
