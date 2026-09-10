package com.tailtopia.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.account.domain.AdminPermissions;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：页面目录（Story 1.5 AC2）——归属码覆盖 AdminPermissions.ALL 每一个码恰好一次；沿用码 ⊆ ALL；三语页面名齐备。 */
class AdminPageCatalogTest {

    @Test
    void ownedCodesCoverAllPermissionsExactlyOnce() {
        List<String> owned = AdminPageCatalog.allCodes();
        assertThat(new HashSet<>(owned)).as("目录里有码被两个页面同时归属").hasSameSizeAs(owned);
        assertThat(owned).as("目录归属码 ≠ AdminPermissions.ALL（漏码或多码）")
                .containsExactlyInAnyOrderElementsOf(AdminPermissions.ALL);
        assertThat(owned).contains(AdminPermissions.CONTENT_MANUAL_REVIEW,
                AdminPermissions.PLACE_MANAGE, AdminPermissions.COMMENT_VIRTUAL_POST);
    }

    @Test
    void sharedCodesAreRegisteredAndOwnedElsewhere() {
        Set<String> owned = new HashSet<>(AdminPageCatalog.allCodes());
        for (AdminPageCatalog.Page p : AdminPageCatalog.PAGES) {
            for (String c : p.sharedCodes()) {
                assertThat(AdminPermissions.isValid(c)).as(p.key() + " 沿用了不在册的码 " + c).isTrue();
                assertThat(owned).as(p.key() + " 沿用的码 " + c + " 没有归属页面").contains(c);
                assertThat(p.ownedCodes()).as(p.key() + " 同一码既归属又沿用").doesNotContain(c);
            }
        }
    }

    @Test
    void everyPageHasKnownGroupAndStableKey() {
        Set<String> groups = new HashSet<>();
        AdminPageCatalog.GROUPS.forEach(g -> groups.add(g.key()));
        assertThat(AdminPageCatalog.GROUPS).hasSize(8);
        Set<String> keys = new HashSet<>();
        for (AdminPageCatalog.Page p : AdminPageCatalog.PAGES) {
            assertThat(groups).as(p.key() + " 的分组不在 8 组内").contains(p.group());
            assertThat(keys.add(p.key())).as("页面 key 重复 " + p.key()).isTrue();
            assertThat(p.key()).matches("[a-z0-9-]+");
        }
        assertThat(AdminPageCatalog.byGroup().values().stream().mapToInt(List::size).sum())
                .isEqualTo(AdminPageCatalog.PAGES.size());
    }

    @Test
    void everyPageAndGroupHasLabelsInAllLocales() throws Exception {
        for (String locale : List.of("zh_CN", "en", "id")) {
            Properties p = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/i18n/messages_" + locale + ".properties")) {
                assertThat(in).isNotNull();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (AdminPageCatalog.Group g : AdminPageCatalog.GROUPS) {
                assertThat(p.getProperty(g.titleKey())).as(locale + " 缺 " + g.titleKey()).isNotBlank();
            }
            for (AdminPageCatalog.Page page : AdminPageCatalog.PAGES) {
                assertThat(p.getProperty(page.titleKey())).as(locale + " 缺 " + page.titleKey()).isNotBlank();
            }
            for (String code : AdminPermissions.ALL) {
                assertThat(p.getProperty("perm." + code)).as(locale + " 缺 perm." + code).isNotBlank();
            }
        }
    }

    // ---- Story 2.2：侧栏元数据 ----

    @Test
    void navPagesHaveRouteUniqueActiveKeyRegisteredCodesAndLabels() throws Exception {
        Set<String> actives = new HashSet<>();
        Properties zh = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/i18n/messages_zh_CN.properties")) {
            zh.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (AdminPageCatalog.Page p : AdminPageCatalog.navPages()) {
            assertThat(p.route()).as(p.key() + " 侧栏项无路由").startsWith("/admin");
            assertThat(p.activeKey()).as(p.key() + " 无 activeKey").isNotBlank();
            assertThat(actives.add(p.activeKey())).as("activeKey 重复 " + p.activeKey()).isTrue();
            assertThat(zh.getProperty(p.navKey())).as("缺导航文案 " + p.navKey()).isNotBlank();
            for (String c : p.navCodes()) {
                assertThat(AdminPermissions.isValid(c)).as(p.key() + " 入口门码不在册 " + c).isTrue();
            }
        }
        // 现状全部 Controller 的 active 值都必须能落到某个侧栏项（否则该页打开时组不展开）。
        for (String active : List.of("accounts", "ai-orders", "algo-params", "anomalies", "audit-logs", "comments", "config",
                "consult-orders", "consult-sessions", "content", "content-pins", "content-tags",
                // ⛔ "online" 已移除：V1.3.0 Story 9.1a 把在线状态并进兽医列表与抽屉，整页路由删除。
                "dashboard", "failed-requests", "manual-review", "payments", "red-overage", "refunds",
                "roles", "seed", "seed-batches", "settlements", "shopBanners", "shopInventory", "shopMargin", "shopOrders",
                "shopProducts", "shopReconciliation", "shopRepurchase", "shopReturns", "shopShipping", "shopTurnover",
                "shopOrderExceptions", "shopPrecedents", "support-tickets", "tickets", "user-tags", "users", "vets",
                "virtual-accounts", "warm-replies", "places")) {
            assertThat(actives).as("active=" + active + " 没有对应侧栏项").contains(active);
        }
    }

    @Test
    void pagesWithoutRouteOrPendingStoriesStayOutOfNav() {
        // 页面内区块 / 抽屉页签无路由，不入侧栏（warm-replies 4.4、places 5.2 已落地入栏）。
        for (String key : List.of("throttles", "user-phone", "vet-qualification", "ratings", "shop-cost", "share-reward")) {
            AdminPageCatalog.Page p = AdminPageCatalog.PAGES.stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
            assertThat(p.inNav()).as(key + " 不该在侧栏").isFalse();
        }
    }
}
