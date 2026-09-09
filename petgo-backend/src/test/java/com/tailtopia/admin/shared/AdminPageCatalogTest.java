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
}
