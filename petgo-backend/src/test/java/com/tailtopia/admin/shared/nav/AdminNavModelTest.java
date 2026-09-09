package com.tailtopia.admin.shared.nav;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.shared.AdminPageCatalog;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：侧栏可见性（Story 2.2 AC1）——超管全见；组 = 项并集；无项整组不渲染；仅超管页；商城组 active 前缀；8 组顺序。 */
class AdminNavModelTest {

    @Test
    void superAdminSeesEveryNavPageInCatalogOrder() {
        List<AdminNavModel.NavGroup> groups = AdminNavModel.build(Set.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN"));
        assertThat(groups).extracting(g -> g.group().key()).containsExactly(
                "overview", "inbox", "content", "users", "orders", "shop", "vet", "config");
        int pages = groups.stream().mapToInt(g -> g.pages().size()).sum();
        assertThat(pages).isEqualTo(AdminPageCatalog.navPages().size());
        assertThat(groups.get(1).pages()).extracting(AdminPageCatalog.Page::key)
                .containsExactly("manual-review", "tickets", "anomalies", "support-tickets", "refunds", "warm-replies"); // 4.4 第 6 项
    }

    @Test
    void contentOnlyStaffSeesOverviewAndOnlyItsGroups() {
        // UI 稿 0-2：仅持内容线权限 → 其余组整组不渲染（不是置灰）；待办中心只剩有权限的队列。
        List<AdminNavModel.NavGroup> groups = AdminNavModel.build(
                Set.of("ROLE_ADMIN", AdminPermissions.CONTENT_VIEW, AdminPermissions.CONTENT_MANUAL_REVIEW));
        assertThat(groups).extracting(g -> g.group().key()).containsExactly("overview", "inbox", "content");
        assertThat(groups.get(1).pages()).extracting(AdminPageCatalog.Page::key).containsExactly("manual-review");
        assertThat(groups.get(2).pages()).extracting(AdminPageCatalog.Page::key).containsExactly("content");
    }

    @Test
    void noQueuePermissionHidesInboxGroupEntirely() {
        List<AdminNavModel.NavGroup> groups = AdminNavModel.build(Set.of("ROLE_ADMIN", AdminPermissions.CONFIG_VIEW));
        assertThat(groups).extracting(g -> g.group().key()).containsExactly("overview", "shop", "config");
        assertThat(groups.stream().noneMatch(AdminNavModel.NavGroup::isInbox)).isTrue();
        // config.view 也开着复购看板 / 运费配置（与 Controller 入口门同集）。
        assertThat(groups.get(1).pages()).extracting(AdminPageCatalog.Page::key)
                .containsExactly("repurchase-dashboard", "shop-shipping");
    }

    @Test
    void rolesPageIsSuperAdminOnlyAndDashboardIsForEveryone() {
        List<AdminNavModel.NavGroup> staff = AdminNavModel.build(
                Set.of("ROLE_ADMIN", AdminPermissions.ADMIN_VIEW_ACCOUNTS, AdminPermissions.ADMIN_VIEW_LOGS));
        assertThat(staff.get(0).pages()).extracting(AdminPageCatalog.Page::key).containsExactly("dashboard");
        AdminNavModel.NavGroup config = staff.get(staff.size() - 1);
        assertThat(config.group().key()).isEqualTo("config");
        assertThat(config.pages()).extracting(AdminPageCatalog.Page::key).containsExactly("audit-logs", "accounts");
    }

    @Test
    void groupContainsActiveAndShopUsesPrefix() {
        List<AdminNavModel.NavGroup> groups = AdminNavModel.build(Set.of("ROLE_SUPER_ADMIN"));
        AdminNavModel.NavGroup shop = groups.stream().filter(g -> g.group().key().equals("shop")).findFirst().orElseThrow();
        assertThat(shop.contains("shopWhateverNewPage")).isTrue();
        assertThat(shop.contains("content")).isFalse();
        AdminNavModel.NavGroup vet = groups.stream().filter(g -> g.group().key().equals("vet")).findFirst().orElseThrow();
        assertThat(vet.contains("online")).isTrue();
        assertThat(vet.contains(null)).isFalse();
    }
}
