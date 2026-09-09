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
            List<String> sharedCodes, boolean superAdminOnly,
            String navKey, String activeKey, List<String> navCodes, boolean legacy) {

        /** Story 1.5 形态（无导航信息）。 */
        public Page(String key, String group, String route, List<String> viewCodes, List<String> editCodes,
                List<String> otherCodes, List<String> sharedCodes, boolean superAdminOnly) {
            this(key, group, route, viewCodes, editCodes, otherCodes, sharedCodes, superAdminOnly,
                    null, null, List.of(), false);
        }

        public String titleKey() {
            return "admin.page." + key;
        }

        /** 是否在侧栏渲染（Story 2.2）：有导航文案 key 且有路由。 */
        public boolean inNav() {
            return navKey != null && route != null;
        }

        /**
         * 挂上侧栏信息（Story 2.2）。{@code navCodes} = 该页入口门（Controller {@code @PreAuthorize}）里的权限码集合，
         * 任一命中即可见；空集 = 全员可见（如概览）；{@code superAdminOnly} 页只对超管可见。
         * 🛡 必须与 Controller 的 @PreAuthorize <b>逐字同集</b>——L1 {@code AdminPagesRenderSmokeTest.everySidebarLinkMatchesItsPageGate}
         * 双向比对（能进却看不见 / 看得见点进去 403 都红）。
         */
        public Page nav(String navKey, String activeKey, String... navCodes) {
            return new Page(key, group, route, viewCodes, editCodes, otherCodes, sharedCodes, superAdminOnly,
                    navKey, activeKey, List.of(navCodes), legacy);
        }

        /** 本版退役页（PRD AB-19A）：侧栏暂保留入口，由对应退役 story 删除。 */
        public Page legacyPage() {
            return new Page(key, group, route, viewCodes, editCodes, otherCodes, sharedCodes, superAdminOnly,
                    navKey, activeKey, navCodes, true);
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

    /** 本版退役但仍在线的页面（仅导航 + 沿用码，不归属任何码；Story 2.2 加，退役 story 删）。 */
    private static Page legacy(String key, String group, String route, String navKey, String activeKey,
            String... codes) {
        return new Page(key, group, route, List.of(), List.of(), List.of(), List.of(codes), false)
                .nav(navKey, activeKey, codes).legacyPage();
    }

    /** 页面目录（UI 稿 7-9 行序）。 */
    public static final List<Page> PAGES = List.of(
            // 📊 概览：查看 = 全员（无码，与原首页一致）；付费卡沿用支付记录页的 payment.view（Story 3.5 / D-17，不另设码）
            new Page("dashboard", G_OVERVIEW, "/admin", List.of(), List.of(), List.of(), List.of(PAYMENT_VIEW), false).nav("admin.nav.dashboard", "dashboard"),
            // 📥 待办中心
            //   content.view_reports：旧举报队列（/admin/reports 已退役）码，暂挂统一复核「其他操作」（D-46）。
            page("manual-review", G_INBOX, "/admin/manual-review",
                    List.of(CONTENT_MANUAL_REVIEW), List.of(), List.of(CONTENT_TAKEDOWN, CONTENT_VIEW_REPORTS)).nav("admin.nav.review", "manual-review", CONTENT_MANUAL_REVIEW, CONTENT_TAKEDOWN),
            mixed("tickets", G_INBOX, "/admin/tickets",
                    List.of(CONTENT_VIEW_TICKETS), List.of(), List.of(CONTENT_DISPOSE_ACCOUNT), List.of(USER_DEACTIVATE)).nav("admin.nav.tickets", "tickets", CONTENT_VIEW_TICKETS),
            page("anomalies", G_INBOX, "/admin/anomalies",
                    List.of(CONSULT_VIEW_ANOMALIES), List.of(CONSULT_HANDLE), List.of()).nav("admin.nav.anomalies", "anomalies", CONSULT_VIEW_ANOMALIES),
            page("support-tickets", G_INBOX, "/admin/support-tickets",
                    List.of(SUPPORT_VIEW), List.of(SUPPORT_HANDLE), List.of(REFUND_SUBMIT)).nav("admin.nav.supportTickets", "support-tickets", SUPPORT_VIEW, SUPPORT_HANDLE),
            page("refunds", G_INBOX, "/admin/refunds",
                    List.of(REFUND_VIEW), List.of(), List.of(REFUND_APPROVE, REFUND_PAYOUT)).nav("admin.nav.refunds", "refunds", REFUND_VIEW, REFUND_SUBMIT, REFUND_APPROVE, REFUND_PAYOUT),
            // Story 4.4：待办中心第 6 项；查看权即 comment.virtual_post（不另设只读码）
            page("warm-replies", G_INBOX, "/admin/warm-replies",
                    List.of(COMMENT_VIRTUAL_POST), List.of(), List.of()).nav("admin.nav.warmReplies", "warm-replies", COMMENT_VIRTUAL_POST),
            // ✍️ 内容
            page("content", G_CONTENT, "/admin/content",
                    List.of(CONTENT_VIEW), List.of(),
                    List.of(CONTENT_RESTORE, CONTENT_PROACTIVE_TAKEDOWN, CONTENT_LIST_EXPORT)).nav("admin.nav.content", "content", CONTENT_VIEW, CONTENT_PROACTIVE_TAKEDOWN),
            shared("comments", G_CONTENT, "/admin/comments",
                    List.of(CONTENT_VIEW, CONTENT_PROACTIVE_TAKEDOWN, CONTENT_RESTORE, COMMENT_VIRTUAL_POST)).nav("admin.nav.comments", "comments", CONTENT_PROACTIVE_TAKEDOWN),
            page("content-pins", G_CONTENT, "/admin/content-pins",
                    List.of(CONTENT_PIN_VIEW), List.of(CONTENT_PIN_MANAGE), List.of()).nav("admin.nav.contentPins", "content-pins", CONTENT_PIN_VIEW),
            page("content-tags", G_CONTENT, "/admin/content-tags",
                    List.of(CONTENT_TAG_VIEW), List.of(CONTENT_TAG_MANAGE), List.of()).nav("admin.nav.contentTags", "content-tags", CONTENT_TAG_VIEW),
            page("throttles", G_CONTENT, null,
                    List.of(CONTENT_THROTTLE_VIEW), List.of(CONTENT_THROTTLE_MANAGE), List.of()),
            page("seed-batches", G_CONTENT, "/admin/seed-batches",
                    List.of(VIRTUAL_ACCOUNT_VIEW), List.of(VIRTUAL_ACCOUNT_MANAGE), List.of()).nav("admin.nav.seedBatches", "seed-batches", VIRTUAL_ACCOUNT_MANAGE),
            mixed("seed-post", G_CONTENT, "/admin/seed-post",
                    List.of(), List.of(), List.of(SEED_PUBLISH_AS_REAL),
                    List.of(VIRTUAL_ACCOUNT_VIEW, VIRTUAL_ACCOUNT_MANAGE)).nav("admin.nav.seed", "seed", VIRTUAL_ACCOUNT_MANAGE),
            // Story 5.2：内容组第 7 项「场所管理」；查看即 place.manage（D-9 / D-17 不预授予预置角色）
            page("places", G_CONTENT, "/admin/places",
                    List.of(PLACE_MANAGE), List.of(), List.of()).nav("admin.nav.places", "places", PLACE_MANAGE),
            // ⛔ 本版退役（7.5 并入批量内容页签）：侧栏暂保留，改期/取消 POST 重定向落点见 7.5。
            legacy("content-schedules", G_CONTENT, "/admin/content-schedules", "admin.nav.contentSchedules",
                    "content-schedules", VIRTUAL_ACCOUNT_MANAGE),
            // 👥 用户
            page("users", G_USERS, "/admin/users",
                    List.of(USER_VIEW), List.of(), List.of(USER_DEACTIVATE, USER_DELETE, USER_GRANT_PAWCOIN)).nav("admin.nav.users", "users", USER_VIEW),
            page("user-phone", G_USERS, null,
                    List.of(USER_PHONE_VIEW), List.of(), List.of(USER_PHONE_EXPORT)),
            page("user-tags", G_USERS, "/admin/user-tags",
                    List.of(USER_TAG_VIEW), List.of(USER_TAG_MANAGE), List.of()).nav("admin.nav.userTags", "user-tags", USER_TAG_VIEW),
            shared("virtual-accounts", G_USERS, "/admin/virtual-accounts",
                    List.of(VIRTUAL_ACCOUNT_VIEW, VIRTUAL_ACCOUNT_MANAGE, SEED_PUBLISH_AS_REAL)).nav("admin.nav.virtualAccounts", "virtual-accounts", VIRTUAL_ACCOUNT_VIEW, VIRTUAL_ACCOUNT_MANAGE, SEED_PUBLISH_AS_REAL),
            // 💰 订单与资金
            page("consult-orders", G_ORDERS, "/admin/consult-orders",
                    List.of(ORDER_VIEW), List.of(ORDER_EDIT), List.of(ORDER_EXPORT)).nav("admin.nav.consultOrders", "consult-orders", ORDER_VIEW),
            shared("ai-orders", G_ORDERS, "/admin/ai-orders", List.of(ORDER_VIEW, ORDER_EXPORT)).nav("admin.nav.aiOrders", "ai-orders", ORDER_VIEW),
            page("payments", G_ORDERS, "/admin/payments",
                    List.of(PAYMENT_VIEW), List.of(), List.of(PAYMENT_LIST_EXPORT)).nav("admin.nav.payments", "payments", PAYMENT_VIEW),
            page("settlements", G_ORDERS, "/admin/settlements",
                    List.of(SETTLEMENT_VIEW), List.of(), List.of(SETTLEMENT_PAYOUT)).nav("admin.nav.settlements", "settlements", SETTLEMENT_VIEW),
            page("red-overage", G_ORDERS, "/admin/red-overage",
                    List.of(RISK_VIEW), List.of(RISK_EDIT), List.of()).nav("admin.nav.redOverage", "red-overage", RISK_VIEW),
            // 🛍 商城
            page("shop-products", G_SHOP, "/admin/shop/products",
                    List.of(SHOP_PRODUCT_VIEW), List.of(SHOP_PRODUCT_EDIT), List.of()).nav("admin.nav.shopProducts", "shopProducts", SHOP_PRODUCT_VIEW, SHOP_PRODUCT_EDIT),
            page("shop-inventory", G_SHOP, "/admin/shop/inventory",
                    List.of(SHOP_INVENTORY_VIEW), List.of(SHOP_INVENTORY_EDIT), List.of()).nav("admin.nav.shopInventory", "shopInventory", SHOP_INVENTORY_VIEW, SHOP_INVENTORY_EDIT),
            page("shop-orders", G_SHOP, "/admin/shop/orders",
                    List.of(SHOP_ORDER_VIEW), List.of(SHOP_ORDER_FULFILL), List.of(SHOP_ORDER_PHONE_SEARCH)).nav("admin.nav.shopOrders", "shopOrders", SHOP_ORDER_VIEW, SHOP_ORDER_FULFILL),
            shared("shop-order-exceptions", G_SHOP, "/admin/shop/order-exceptions",
                    List.of(SHOP_ORDER_VIEW, SHOP_ORDER_FULFILL)).nav("admin.nav.shopOrderExceptions", "shopOrderExceptions", SHOP_ORDER_VIEW, SHOP_ORDER_FULFILL),
            shared("shop-returns", G_SHOP, "/admin/shop/returns",
                    List.of(REFUND_VIEW, REFUND_APPROVE, REFUND_PAYOUT)).nav("admin.nav.shopReturns", "shopReturns", REFUND_VIEW, REFUND_APPROVE, REFUND_PAYOUT),
            shared("return-precedents", G_SHOP, "/admin/shop/return-precedents",
                    List.of(REFUND_VIEW, REFUND_APPROVE)).nav("admin.nav.shopPrecedents", "shopPrecedents", REFUND_VIEW, REFUND_APPROVE, REFUND_PAYOUT),
            shared("repurchase-dashboard", G_SHOP, "/admin/shop/repurchase-dashboard",
                    List.of(CONFIG_VIEW, ORDER_VIEW)).nav("admin.nav.shopRepurchase", "shopRepurchase", CONFIG_VIEW, ORDER_VIEW),
            page("shop-finance", G_SHOP, "/admin/shop/margin",
                    List.of(SHOP_FINANCE_VIEW), List.of(), List.of()).nav("admin.nav.shopMargin", "shopMargin", SHOP_FINANCE_VIEW),
            page("shop-cost", G_SHOP, null,
                    List.of(SHOP_COST_VIEW), List.of(SHOP_COST_EDIT), List.of()),
            // 现状独立页（10.3 Banner 抽屉化、经营数据三页合一后由 Epic 10 收口）：侧栏暂保留。
            legacy("shop-banners", G_SHOP, "/admin/shop/banners", "admin.nav.shopBanners", "shopBanners",
                    SHOP_PRODUCT_VIEW, SHOP_PRODUCT_EDIT),
            legacy("shop-turnover", G_SHOP, "/admin/shop/inventory-turnover", "admin.nav.shopTurnover", "shopTurnover",
                    SHOP_FINANCE_VIEW),
            legacy("shop-reconciliation", G_SHOP, "/admin/shop/reconciliation", "admin.nav.shopReconciliation",
                    "shopReconciliation", SHOP_FINANCE_VIEW),
            shared("shop-shipping", G_SHOP, "/admin/shop/shipping", List.of(CONFIG_VIEW, CONFIG_EDIT)).nav("admin.nav.shopShipping", "shopShipping", CONFIG_VIEW, CONFIG_EDIT),
            // 🩺 兽医与问诊
            page("vets", G_VET, "/admin/vets",
                    List.of(VET_VIEW), List.of(), List.of(VET_CREATE, VET_EDIT, VET_BAN, VET_RESET_PASSWORD)).nav("admin.nav.vets", "vets", VET_VIEW),
            page("vet-qualification", G_VET, null,
                    List.of(VET_QUALIFY_VIEW), List.of(VET_QUALIFY), List.of()),
            page("ratings", G_VET, null,
                    List.of(RATING_VIEW), List.of(), List.of()),
            shared("failed-requests", G_VET, "/admin/failed-requests", List.of(VET_VIEW)).nav("admin.nav.failedRequests", "failed-requests", VET_VIEW),
            page("consult-sessions", G_VET, "/admin/consult-sessions",
                    List.of(CONSULT_VIEW_SESSIONS), List.of(), List.of()).nav("admin.nav.sessions", "consult-sessions", CONSULT_VIEW_SESSIONS),
            // ⛔ 本版退役（9.1a 并入兽医列表 / 9.1b 评分并入筛选栏）：侧栏暂保留。
            legacy("online", G_VET, "/admin/vets/online", "admin.nav.online", "online", VET_VIEW),
            legacy("ratings-page", G_VET, "/admin/ratings", "admin.nav.ratings", "ratings", RATING_VIEW),
            // ⚙️ 配置与安全
            page("config", G_CONFIG, "/admin/config",
                    List.of(CONFIG_VIEW), List.of(CONFIG_EDIT), List.of()).nav("admin.nav.config", "config", CONFIG_VIEW, CONFIG_SHARE_REWARD_VIEW, CONFIG_SHARE_REWARD_EDIT),
            page("algo-params", G_CONFIG, "/admin/algo-params",
                    List.of(CONFIG_ALGO_PARAM_VIEW), List.of(CONFIG_ALGO_PARAM_EDIT), List.of()).nav("admin.nav.algoParams", "algo-params", CONFIG_ALGO_PARAM_VIEW),
            page("share-reward", G_CONFIG, null,
                    List.of(CONFIG_SHARE_REWARD_VIEW), List.of(CONFIG_SHARE_REWARD_EDIT), List.of()),
            page("audit-logs", G_CONFIG, "/admin/audit-logs",
                    List.of(ADMIN_VIEW_LOGS), List.of(), List.of()).nav("admin.nav.audit", "audit-logs", ADMIN_VIEW_LOGS),
            page("accounts", G_CONFIG, "/admin/accounts",
                    List.of(ADMIN_VIEW_ACCOUNTS), List.of(), List.of(ADMIN_CREATE_ACCOUNT, ADMIN_DEACTIVATE)).nav("admin.nav.accounts", "accounts", ADMIN_VIEW_ACCOUNTS, ADMIN_CREATE_ACCOUNT),
            new Page("roles", G_CONFIG, "/admin/roles", List.of(), List.of(), List.of(), List.of(), true).nav("admin.nav.roles", "roles"));

    /** 按组分页（组序 = {@link #GROUPS}，组内 = {@link #PAGES} 顺序）。 */
    public static Map<Group, List<Page>> byGroup() {
        Map<Group, List<Page>> m = new LinkedHashMap<>();
        for (Group g : GROUPS) {
            m.put(g, PAGES.stream().filter(p -> p.group().equals(g.key())).toList());
        }
        return m;
    }

    /** 侧栏项（有 navKey 且有路由），保持 PAGES 顺序（Story 2.2）。 */
    public static List<Page> navPages() {
        return PAGES.stream().filter(Page::inNav).toList();
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
