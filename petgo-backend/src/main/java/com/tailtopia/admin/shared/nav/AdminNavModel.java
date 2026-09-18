package com.tailtopia.admin.shared.nav;

import com.tailtopia.admin.shared.AdminPageCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 侧栏渲染模型（V1.3.0 Story 2.2，AD-9）。由 {@link AdminPageCatalog} 驱动，权限表达式<b>只存在于目录一处</b>，
 * 模板不再手写 {@code sec:authorize}。
 *
 * <p>可见性：超管看全部；页面 {@code navCodes} 任一命中即可见（空集 = 全员）；{@code superAdminOnly} 仅超管；
 * <b>组的可见性 = 组内各项的并集</b>，整组无项则整个 {@code <details>} 不渲染（不是置灰）。
 * 纯函数、无 Spring，供 L0 单测。
 */
public final class AdminNavModel {

    /** 一个可见的导航组及其可见项。 */
    public record NavGroup(AdminPageCatalog.Group group, List<AdminPageCatalog.Page> pages) {

        /** 是否含当前页（组自动 open）；商城组沿用 active 以 shop 开头的约定。 */
        public boolean contains(String active) {
            if (active == null) {
                return false;
            }
            if (AdminPageCatalog.G_SHOP.equals(group.key()) && active.startsWith("shop")) {
                return true;
            }
            return pages.stream().anyMatch(p -> active.equals(p.activeKey()));
        }

        public boolean isInbox() {
            return AdminPageCatalog.G_INBOX.equals(group.key());
        }
    }

    public static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    private AdminNavModel() {
    }

    /** 某页对持有 {@code authorities}（authority 字符串集合）的登录者是否可见。 */
    public static boolean visible(AdminPageCatalog.Page page, Set<String> authorities) {
        if (authorities.contains(ROLE_SUPER_ADMIN)) {
            return true;
        }
        if (page.superAdminOnly()) {
            return false;
        }
        if (page.navCodes().isEmpty()) {
            return true;
        }
        return page.navCodes().stream().anyMatch(authorities::contains);
    }

    /** 按目录顺序算出可见组 → 可见项。 */
    public static List<NavGroup> build(Set<String> authorities) {
        List<NavGroup> out = new ArrayList<>();
        for (var e : AdminPageCatalog.byGroup().entrySet()) {
            List<AdminPageCatalog.Page> visiblePages = e.getValue().stream()
                    .filter(AdminPageCatalog.Page::inNav)
                    .filter(p -> visible(p, authorities))
                    .toList();
            if (!visiblePages.isEmpty()) {
                out.add(new NavGroup(e.getKey(), visiblePages));
            }
        }
        return out;
    }
}
