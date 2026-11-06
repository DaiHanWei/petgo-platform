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
    public record NavGroup(AdminPageCatalog.Group group, List<AdminPageCatalog.Page> pages,
            List<AdminPageCatalog.Page> shopTodos) {

        public NavGroup(AdminPageCatalog.Group group, List<AdminPageCatalog.Page> pages) {
            this(group, pages, List.of());
        }

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

    /**
     * V1.4.2 shop-admin Story 5.3 · AD-2：待办中心组末尾「商城待办」的跳转项（不是新 Catalog 页，指向商城组既有页面），
     * 无页面权限不渲染。
     */
    public static final List<String> SHOP_TODO_KEYS = List.of("shop-order-exceptions", "shop-refund-payouts");

    /** 按目录顺序算出可见组 → 可见项。 */
    public static List<NavGroup> build(Set<String> authorities) {
        List<NavGroup> out = new ArrayList<>();
        List<AdminPageCatalog.Page> shopTodos = SHOP_TODO_KEYS.stream()
                .map(k -> AdminPageCatalog.PAGES.stream().filter(p -> p.key().equals(k)).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(p -> visible(p, authorities))
                .toList();
        for (var e : AdminPageCatalog.byGroup().entrySet()) {
            List<AdminPageCatalog.Page> visiblePages = e.getValue().stream()
                    .filter(AdminPageCatalog.Page::inNav)
                    .filter(p -> visible(p, authorities))
                    .toList();
            boolean inbox = AdminPageCatalog.G_INBOX.equals(e.getKey().key());
            if (!visiblePages.isEmpty() || (inbox && !shopTodos.isEmpty())) {
                out.add(new NavGroup(e.getKey(), visiblePages, inbox ? shopTodos : List.of()));
            }
        }
        return out;
    }
}
