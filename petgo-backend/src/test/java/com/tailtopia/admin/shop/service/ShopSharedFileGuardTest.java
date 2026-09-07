package com.tailtopia.admin.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.account.domain.AdminPermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：共享文件的护栏（Story 1.3）。
 *
 * <p>本 Story 同时改 4 个三线共享的文件（{@code AdminPermissions} / {@code AuditActions} /
 * admin 导航 / 三份 i18n）。这些改动<b>出错时不会引起编译或行为报错</b>：
 * 漏一份 i18n 只是该语种显示 raw key；权限码写错只是账号静默失权。
 * 本类是唯一能在 CI 里拦住它们的东西。
 */
class ShopSharedFileGuardTest {

    private static final List<String> LOCALES = List.of(
            "src/main/resources/i18n/messages_en.properties",
            "src/main/resources/i18n/messages_id.properties",
            "src/main/resources/i18n/messages_zh_CN.properties");

    private static Set<String> keysOf(String path) throws IOException {
        Set<String> keys = new HashSet<>();
        for (String line : Files.readAllLines(Path.of(path))) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#") && t.contains("=")) {
                keys.add(t.substring(0, t.indexOf('=')).strip());
            }
        }
        return keys;
    }

    @Test
    @DisplayName("🔴 电商 i18n key 必须三份齐全 —— 漏一份该语种显示 raw key")
    void shopKeysExistInAllThreeLocales() throws IOException {
        Set<String> en = keysOf(LOCALES.get(0));
        Set<String> shopKeys = new TreeSet<>(en.stream()
                .filter(k -> k.startsWith("admin.shop.") || k.startsWith("admin.nav.shop")
                        || k.equals("admin.nav.group.shop"))
                .toList());
        assertThat(shopKeys).as("英文里应已有电商 key").isNotEmpty();

        for (String path : LOCALES) {
            Set<String> ks = keysOf(path);
            Set<String> missing = new TreeSet<>(shopKeys);
            missing.removeAll(ks);
            assertThat(missing).as("%s 缺少电商 key", path).isEmpty();
        }
    }

    @Test
    @DisplayName("🔴 模板引用的每个电商 i18n key 都真实存在 —— 防止拼错导致页面显示 raw key")
    void everyReferencedKeyExists() throws IOException {
        Set<String> en = keysOf(LOCALES.get(0));
        for (String tpl : List.of(
                "src/main/resources/templates/admin/shop-products.html",
                "src/main/resources/templates/admin/shop-product-form.html")) {
            String html = Files.readString(Path.of(tpl));
            var m = java.util.regex.Pattern.compile("#\\{([a-zA-Z0-9._]+)").matcher(html);
            Set<String> missing = new TreeSet<>();
            while (m.find()) {
                if (!en.contains(m.group(1))) {
                    missing.add(m.group(1));
                }
            }
            assertThat(missing).as("%s 引用了不存在的 key", tpl).isEmpty();
        }
    }

    @Test
    @DisplayName("🔴 4 个电商权限码已注册进 ALL —— 未注册则创建账号时被判非法值")
    void shopPermissionsRegistered() {
        for (String p : List.of(AdminPermissions.SHOP_PRODUCT_VIEW,
                AdminPermissions.SHOP_PRODUCT_EDIT,
                AdminPermissions.SHOP_COST_VIEW,
                AdminPermissions.SHOP_COST_EDIT)) {
            assertThat(AdminPermissions.isValid(p)).as("%s 应在 ALL 中", p).isTrue();
        }
    }

    @Test
    @DisplayName("🔴 追加权限码未破坏既有码 —— 删/改一个码 = 已授权账号静默失权")
    void existingPermissionsIntact() {
        // 抽查各模块的既有码，确认追加操作没有误删或改拼写
        for (String p : List.of("vet.view", "vet.qualify", "user.view", "content.view",
                "consult.handle", "support.handle", "refund.approve", "config.edit",
                "order.view", "payment.view", "risk.view", "virtual_account.manage",
                "admin.create_account", "admin.view_logs")) {
            assertThat(AdminPermissions.isValid(p)).as("既有权限码 %s 不得丢失", p).isTrue();
        }
    }

    @Test
    @DisplayName("🔒 进货价权限与商品权限是分开的两个码 —— 不得合并")
    void costPermissionIsSeparate() {
        assertThat(AdminPermissions.SHOP_COST_VIEW).isNotEqualTo(AdminPermissions.SHOP_PRODUCT_VIEW);
        assertThat(AdminPermissions.SHOP_COST_EDIT).isNotEqualTo(AdminPermissions.SHOP_PRODUCT_EDIT);
    }

    @Test
    @DisplayName("🔴 admin 导航追加在末尾，且既有分组一个没少")
    void navAppendedWithoutDisturbingExisting() throws IOException {
        String layout = Files.readString(
                Path.of("src/main/resources/templates/admin/layout.html"));
        for (String g : List.of("admin.nav.group.content", "admin.nav.group.security")) {
            assertThat(layout).as("既有导航分组 %s 不得丢失", g).contains(g);
        }
        assertThat(layout).contains("admin.nav.group.shop");
        // 电商分组必须在既有 security 分组之后（即追加到末尾，未插队）
        assertThat(layout.indexOf("admin.nav.group.shop"))
                .as("电商导航应追加在末尾，不得插到既有分组之前")
                .isGreaterThan(layout.indexOf("admin.nav.group.security"));
    }
}
