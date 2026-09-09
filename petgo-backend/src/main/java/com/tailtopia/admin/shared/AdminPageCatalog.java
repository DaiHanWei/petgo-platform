package com.tailtopia.admin.shared;

import static com.tailtopia.admin.account.domain.AdminPermissions.*;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台页面目录（V1.3.0 Story 1.5，架构 §结构树 admin/shared）——「页面 ↔ 权限码 ↔ 导航组」的单一数据源。
 * 角色配置页的权限矩阵（行 = 页面，列 = 查看 / 编辑 / 其他操作）、Epic 2 新导航（Story 2.2）、
 * 1-6 账号页权限面板重排、Story 2.1 写操作清单「所属页面」列都从这里取。
 *
 * <p><b>归属规则</b>：每个权限码在整个目录里<b>恰好属于一个页面</b>（{@link Page#viewCodes} / {@link Page#editCodes} /
 * {@link Page#otherCodes} 三列之一，矩阵里对应恰好一个复选框）；多个页面共用同一码的现状不规整项
 * （PRD FR-21A-7：评论管理沿用 content.view、Toko 退货 / 开封判例共用 refund.*、异常订单与履约同码、运费用 config.*、
 * 复购看板 config.view 或 order.view、未成功请求沿用 vet.view）记在 {@link Page#sharedCodes}——矩阵里只展示「沿用 …」
 * 文字 + 注脚，不再出复选框。L0 {@code AdminPageCatalogTest} 断言归属码 == {@code AdminPermissions.ALL} 且无重复。
 *
 * <p>分组按 UI 稿 7-9 的 8 个导航组（不是现状 layout.html 的 10 组）。
 */
public final class AdminPageCatalog {

    /** 8 个导航组（顺序即展示顺序）。titleKey = admin.group.&lt;key&gt;。 */
    public record Group(String key, String titleKey) {
    }

    /**
     * 一个后台页面（或页面内的权限区块）。
     *
     * @param key            稳定标识，titleKey = admin.page.&lt;key&gt;
     * @param group          所属导航组 key
     * @param route          路由（页面内区块 / 抽屉页签为 null）
     * @param viewCodes      本页归属的「查看」列码
     * @param editCodes      本页归属的「编辑」列码
     * @param otherCodes     本页归属的「其他操作」列码
     * @param sharedCodes    本页沿用的其它页面归属码（只展示、不出复选框；带注脚）
     * @param superAdminOnly 仅超管可进（无权限码，如角色配置页本身）
     */
    public record Page(String key, String group, String route,
            List<String> viewCodes, List<String> editCodes, List<String> otherCodes,
            List<String> sharedCodes, boolean superAdminOnly) {

        public String titleKey() {
            return "admin.page." + key;
        }

        /** 本页归属的全部码（三列合并，顺序稳定）。 */
        public List<String> ownedCodes() {
            List<String> all = new java.util.ArrayList<>(viewCodes);
            all.addAll(editCodes);
            all.addAll(otherCodes);
            return List.copyOf(all);
        }

        public boolean hasSharedOnly() {
            return viewCodes.isEmpty() && editCodes.isEmpty() && otherCodes.isEmpty() && !sharedCodes.isEmpty();
        }
    }

    public static final String G_OVERVIEW = "overview";
    public static final String G_INBOX = "inbox";
    public static final String G_CONTENT = "content";
    public static final String G_USERS = "users";
    public static final String G_ORDERS = "orders";
    public static final String G_SHOP = "shop";
    public static final String G_VET = "vet";
    public static final String G_CONFIG = "config";

    public static final List<Group> GROUPS = List.of(
            new Group(G_OVERVIEW, "admin.group.overview"),
            new Group(G_INBOX, "admin.group.inbox"),
            new Group(G_CONTENT, "admin.group.content"),
            new Group(G_USERS, "admin.group.users"),
            new Group(G_ORDERS, "admin.group.orders"),
            new Group(G_SHOP, "admin.group.shop"),
            new Group(G_VET, "admin.group.vet"),
            new Group(G_CONFIG, "admin.group.config"));

    private static Page page(String key, String group, String route,
            List<String> view, List<String> edit, List<String> other) {
        return new Page(key, group, route, view, edit, other, List.of(), false);
    }

    private static Page shared(String key, String group, String route, List<String> sharedCodes) {
        return new Page(key, group, route, List.of(), List.of(), List.of(), sharedCodes, false);
    }

    private static Page mixed(String key, String group, String route,
            List<String> view, List<String> edit, List<String> other, List<String> sharedCodes) {
        return new Page(key, group, route, view, edit, other, sharedCodes, false);
    }

    /** 页面目录（UI 稿 7-9 行序）。 */
    public static final List<Page> PAGES = List.of(
            // 📊 概览
            new Page("dashboard", G_OVERVIEW, "/admin", List.of(), List.of(), List.of(), List.of(), false),
            // 📥 待办中心
            //   content.view_reports：旧举报队列（/admin/reports 已退役）码，暂挂统一复核「其他操作」（D-46）。
            page("manual-review", G_INBOX, "/admin/manual-review",
                    List.of(CONTENT_MANUAL_REVIEW), List.of(), List.of(CONTENT_TAKEDOWN, CONTENT_VIEW_REPORTS)),
            mixed("tickets", G_INBOX, "/admin/tickets",
                    List.of(CONTENT_VIEW_TICKETS), List.of(), List.of(CONTENT_DISPOSE_ACCOUNT), List.of(USER_DEACTIVATE)),
            page("anomalies", G_INBOX, "/admin/anomalies",
                    List.of(CONSULT_VIEW_ANOMALIES), List.of(CONSULT_HANDLE), List.of()),
            page("support-tickets", G_INBOX, "/admin/support-tickets",
                    List.of(SUPPORT_VIEW), List.of(SUPPORT_HANDLE), List.of(REFUND_SUBMIT)),
            page("refunds", G_INBOX, "/admin/refunds",
                    List.of(REFUND_VIEW), List.of(), List.of(REFUND_APPROVE, REFUND_PAYOUT)),
            page("warm-replies", G_INBOX, "/admin/warm-replies",
                    List.of(COMMENT_VIRTUAL_POST), List.of(), List.of()),
            // ✍️ 内容
            page("content", G_CONTENT, "/admin/content",
                    List.of(CONTENT_VIEW), List.of(),
                    List.of(CONTENT_RESTORE, CONTENT_PROACTIVE_TAKEDOWN, CONTENT_LIST_EXPORT)),
            shared("comments", G_CONTENT, "/admin/comments",
                    List.of(CONTENT_VIEW, CONTENT_PROACTIVE_TAKEDOWN, CONTENT_RESTORE, COMMENT_VIRTUAL_POST)),
            page("content-pins", G_CONTENT, "/admin/content-pins",
                    List.of(CONTENT_PIN_VIEW), List.of(CONTENT_PIN_MANAGE), List.of()),
            page("content-tags", G_CONTENT, "/admin/content-tags",
                    List.of(CONTENT_TAG_VIEW), List.of(CONTENT_TAG_MANAGE), List.of()),
            page("throttles", G_CONTENT, null,
                    List.of(CONTENT_THROTTLE_VIEW), List.of(CONTENT_THROTTLE_MANAGE), List.of()),
            page("seed-batches", G_CONTENT, "/admin/seed-batches",
                    List.of(VIRTUAL_ACCOUNT_VIEW), List.of(VIRTUAL_ACCOUNT_MANAGE), List.of()),
            mixed("seed-post", G_CONTENT, "/admin/seed-post",
                    List.of(), List.of(), List.of(SEED_PUBLISH_AS_REAL),
                    List.of(VIRTUAL_ACCOUNT_VIEW, VIRTUAL_ACCOUNT_MANAGE)),
            page("places", G_CONTENT, "/admin/places",
                    List.of(PLACE_MANAGE), List.of(), List.of()),
            // 👥 用户
            page("users", G_USERS, "/admin/users",
                    List.of(USER_VIEW), List.of(), List.of(USER_DEACTIVATE, USER_DELETE, USER_GRANT_PAWCOIN)),
            page("user-phone", G_USERS, null,
                    List.of(USER_PHONE_VIEW), List.of(), List.of(USER_PHONE_EXPORT)),
            page("user-tags", G_USERS, "/admin/user-tags",
                    List.of(USER_TAG_VIEW), List.of(USER_TAG_MANAGE), List.of()),
            shared("virtual-accounts", G_USERS, "/admin/virtual-accounts",
                    List.of(VIRTUAL_ACCOUNT_VIEW, VIRTUAL_ACCOUNT_MANAGE, SEED_PUBLISH_AS_REAL)),
            // 💰 订单与资金
            page("consult-orders", G_ORDERS, "/admin/consult-orders",
                    List.of(ORDER_VIEW), List.of(ORDER_EDIT), List.of(ORDER_EXPORT)),
            shared("ai-orders", G_ORDERS, "/admin/ai-orders", List.of(ORDER_VIEW, ORDER_EXPORT)),
            page("payments", G_ORDERS, "/admin/payments",
                    List.of(PAYMENT_VIEW), List.of(), List.of(PAYMENT_LIST_EXPORT)),
            page("settlements", G_ORDERS, "/admin/settlements",
                    List.of(SETTLEMENT_VIEW), List.of(), List.of(SETTLEMENT_PAYOUT)),
            page("red-overage", G_ORDERS, "/admin/red-overage",
                    List.of(RISK_VIEW), List.of(RISK_EDIT), List.of()),
            // 🛍 商城
            page("shop-products", G_SHOP, "/admin/shop/products",
                    List.of(SHOP_PRODUCT_VIEW), List.of(SHOP_PRODUCT_EDIT), List.of()),
            page("shop-inventory", G_SHOP, "/admin/shop/inventory",
                    List.of(SHOP_INVENTORY_VIEW), List.of(SHOP_INVENTORY_EDIT), List.of()),
            page("shop-orders", G_SHOP, "/admin/shop/orders",
                    List.of(SHOP_ORDER_VIEW), List.of(SHOP_ORDER_FULFILL), List.of(SHOP_ORDER_PHONE_SEARCH)),
            shared("shop-order-exceptions", G_SHOP, "/admin/shop/order-exceptions",
                    List.of(SHOP_ORDER_VIEW, SHOP_ORDER_FULFILL)),
            shared("shop-returns", G_SHOP, "/admin/shop/returns",
                    List.of(REFUND_VIEW, REFUND_APPROVE, REFUND_PAYOUT)),
            shared("return-precedents", G_SHOP, "/admin/shop/return-precedents",
                    List.of(REFUND_VIEW, REFUND_APPROVE)),
            shared("repurchase-dashboard", G_SHOP, "/admin/shop/repurchase-dashboard",
                    List.of(CONFIG_VIEW, ORDER_VIEW)),
            page("shop-finance", G_SHOP, "/admin/shop/margin",
                    List.of(SHOP_FINANCE_VIEW), List.of(), List.of()),
            page("shop-cost", G_SHOP, null,
                    List.of(SHOP_COST_VIEW), List.of(SHOP_COST_EDIT), List.of()),
            shared("shop-shipping", G_SHOP, "/admin/shop/shipping", List.of(CONFIG_VIEW, CONFIG_EDIT)),
            // 🩺 兽医与问诊
            page("vets", G_VET, "/admin/vets",
                    List.of(VET_VIEW), List.of(), List.of(VET_CREATE, VET_EDIT, VET_BAN, VET_RESET_PASSWORD)),
            page("vet-qualification", G_VET, null,
                    List.of(VET_QUALIFY_VIEW), List.of(VET_QUALIFY), List.of()),
            page("ratings", G_VET, null,
                    List.of(RATING_VIEW), List.of(), List.of()),
            shared("failed-requests", G_VET, "/admin/failed-requests", List.of(VET_VIEW)),
            page("consult-sessions", G_VET, "/admin/consult-sessions",
                    List.of(CONSULT_VIEW_SESSIONS), List.of(), List.of()),
            // ⚙️ 配置与安全
            page("config", G_CONFIG, "/admin/config",
                    List.of(CONFIG_VIEW), List.of(CONFIG_EDIT), List.of()),
            page("algo-params", G_CONFIG, "/admin/algo-params",
                    List.of(CONFIG_ALGO_PARAM_VIEW), List.of(CONFIG_ALGO_PARAM_EDIT), List.of()),
            page("share-reward", G_CONFIG, null,
                    List.of(CONFIG_SHARE_REWARD_VIEW), List.of(CONFIG_SHARE_REWARD_EDIT), List.of()),
            page("audit-logs", G_CONFIG, "/admin/audit-logs",
                    List.of(ADMIN_VIEW_LOGS), List.of(), List.of()),
            page("accounts", G_CONFIG, "/admin/accounts",
                    List.of(ADMIN_VIEW_ACCOUNTS), List.of(), List.of(ADMIN_CREATE_ACCOUNT, ADMIN_DEACTIVATE)),
            new Page("roles", G_CONFIG, "/admin/roles", List.of(), List.of(), List.of(), List.of(), true));

    /** 按组分页（组序 = {@link #GROUPS}，组内 = {@link #PAGES} 顺序）。 */
    public static Map<Group, List<Page>> byGroup() {
        Map<Group, List<Page>> m = new LinkedHashMap<>();
        for (Group g : GROUPS) {
            m.put(g, PAGES.stream().filter(p -> p.group().equals(g.key())).toList());
        }
        return m;
    }

    /** 目录里归属的全部权限码（顺序稳定；L0 测试断言 == AdminPermissions.ALL 且无重复）。 */
    public static List<String> allCodes() {
        return PAGES.stream().flatMap(p -> p.ownedCodes().stream()).toList();
    }

    /** 沿用码（不规整项）去重集合。 */
    public static Set<String> sharedCodes() {
        Set<String> s = new LinkedHashSet<>();
        PAGES.forEach(p -> s.addAll(p.sharedCodes()));
        return s;
    }

    private AdminPageCatalog() {
    }
}
