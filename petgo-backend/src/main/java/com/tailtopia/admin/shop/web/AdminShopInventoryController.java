package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.shop.dto.InventoryRowView;
import com.tailtopia.admin.shop.service.AdminShopInventoryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.InventoryMovement;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryMovementService;
import com.tailtopia.shared.i18n.Messages;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 库存管理与采购入库（Story 1.4，AB-10C）。模块 10 的第二个页面。
 *
 * <p>🔒 <b>采购入库需双权限</b>：{@code shop.inventory_edit} + {@code shop.cost_edit}。
 * 进货单价按 S-9 不允许留空，而单价是商业敏感数据（2026-08-17 产品确认）。
 * 退货入库单价由系统带出，只需 {@code shop.inventory_edit}。
 *
 * <p>🔒 进货单价的门控在<b>服务端</b>：无 {@code shop.cost_view} 时相关数据<b>根本不放进 model</b>
 * ——模板里用 {@code th:if} 隐藏可通过看源码绕过（沿用 Story 1.3 的处置）。
 */
@Controller
public class AdminShopInventoryController {

    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.inventory_view') "
                    + "or hasAuthority('shop.inventory_edit')";
    private static final String EDIT_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.inventory_edit')";

    private static final int MOVEMENT_PAGE_SIZE = 50;

    private static final String REDIRECT_LIST = "redirect:/admin/shop/inventory";

    private final InventoryMovementService movements;
    private final AdminShopInventoryService view;
    private final ShopSkuRepository skus;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminShopInventoryController(InventoryMovementService movements,
            AdminShopInventoryService view, ShopSkuRepository skus, Messages msg) {
        this.movements = movements;
        this.view = view;
        this.skus = skus;
        this.msg = msg;
    }

    // ---------- 列表：三个数不合并显示 ----------

    /**
     * B18 列表（V1.3.0 Story 10.4 · AC1，模板 B）。
     *
     * <p>🔴 <b>抽屉取数不走这条 mapping</b>，走 {@code GET inventory/{skuId}/movements} 的
     * {@code HX-Request} 分支（AD-9 的 {@code …/{id}/drawer} 惯例让位于「零新端点」，D-43）：
     * 抽屉上半本来就是那条流水，复用它比另开一条省一个端点，也保证抽屉里的流水与流水页同源。
     */
    @GetMapping("/admin/shop/inventory")
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "open", required = false) Long open,
            HxRequest hx, Model model) {
        Page<InventoryRowView> rows = view.page(page);
        model.addAttribute("rows", rows.getContent());
        model.addAttribute("page", rows.getNumber());
        model.addAttribute("hasNext", rows.hasNext());
        populatePermissions(admin, model);
        model.addAttribute("open", open);
        model.addAttribute("active", "shopInventory");
        if (hx.isHtmx()) {
            // ⚠️ 翻页**不重算摘要条**：它是全表聚合（一次 sku_inventory 全表扫描），
            //    而翻页压根不会改变它 —— rows 片段也不引用它。算了就是白扫一遍。
            return "admin/fragments/shop-inventory-list :: rows";
        }
        model.addAttribute("summary", view.summary());
        return "admin/shop-inventory";
    }

    /**
     * 三个权限位。
     *
     * <p>🔒 <b>{@code canPurchase} 是双权限</b>（{@code inventory_edit} + {@code cost_edit}）：
     * 采购入库要填进货单价，而单价按 S-9 不允许留空。服务端在写端点里<b>独立再判一次</b>，
     * 页面上的禁用只是第一层。
     */
    private void populatePermissions(AdminUserDetails admin, Model model) {
        model.addAttribute("canEdit", has(admin, AdminPermissions.SHOP_INVENTORY_EDIT));
        model.addAttribute("canPurchase", has(admin, AdminPermissions.SHOP_INVENTORY_EDIT)
                && has(admin, AdminPermissions.SHOP_COST_EDIT));
        model.addAttribute("canViewCost", has(admin, AdminPermissions.SHOP_COST_VIEW));
    }

    // ---------- 流水（含前后值，可审计） ----------

    /**
     * 同一条 mapping 两种响应（AC2 / AC3）：{@code HX-Request} 返<b>抽屉</b>片段
     * （流水摘要最近 {@value AdminShopInventoryService#DRAWER_MOVEMENTS} 条 + 四操作页签），
     * 否则返<b>整页流水</b>（模板 B 只读，最近 {@value #MOVEMENT_PAGE_SIZE} 条）。
     *
     * <p>🔴 <b>不新建全局流水路由</b> {@code /admin/shop/inventory-movements}（D-43）：
     * 那是新增端点。UI 稿 5-7 的「独立账本页」按现状的 per-SKU 路由实现，
     * 抽屉里的「查看全部流水」链到它。
     */
    @GetMapping("/admin/shop/inventory/{skuId}/movements")
    @PreAuthorize(VIEW_AUTH)
    public String movements(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long skuId, HxRequest hx, Model model) {
        model.addAttribute("active", "shopInventory");
        if (hx.isHtmx()) {
            populateDrawer(admin, skuId, model);
            return "admin/fragments/drawer-shop-inventory :: panel";
        }
        populateMovements(admin, skuId, MOVEMENT_PAGE_SIZE, model);
        return "admin/shop-inventory-movements";
    }

    /** 抽屉体：上半流水摘要 + 下半四操作页签（AC2）。 */
    private void populateDrawer(AdminUserDetails admin, long skuId, Model model) {
        populateMovements(admin, skuId, AdminShopInventoryService.DRAWER_MOVEMENTS, model);
        model.addAttribute("row", view.row(skuId));
        populatePermissions(admin, model);
    }

    private void populateMovements(AdminUserDetails admin, long skuId, int limit, Model model) {
        ShopSku sku = skus.findById(skuId)
                .orElseThrow(() -> AppException.notFound("SKU 不存在").code("admin.err.product.skuNotFound2"));
        boolean canViewCost = has(admin, AdminPermissions.SHOP_COST_VIEW);

        List<InventoryMovement> rows = movements.recentMovements(skuId, limit);
        model.addAttribute("sku", sku);
        model.addAttribute("movements", rows);
        model.addAttribute("canViewCost", canViewCost);
        // 🔒 无 cost_view 时进货单价【根本不进 model】，不是靠模板隐藏
        model.addAttribute("costByMovementId", canViewCost
                ? rows.stream().filter(m -> m.getCostPrice() != null)
                        .collect(Collectors.toMap(InventoryMovement::getId,
                                InventoryMovement::getCostPrice, (a, b) -> a))
                : Map.of());
    }

    // ---------- 四条增减路径中的三条（第四条退货质检入库属 Story 5.4） ----------

    /**
     * 🔴 <b>四个写端点的 {@code skuId} 走表单字段，不走 path variable。</b>
     *
     * <p>先前的写法是把 URL 里的占位 {@code /0/} 用 {@code onsubmit} 的 JS 就地替换成选中的 skuId。
     * 那个替换<b>不幂等</b>：替换后 {@code action} 里已不含 {@code /0/}，同一份 DOM 若被再次提交
     * （浏览器 bfcache 回退最典型），{@code replace} 找不到目标、{@code action} 原封不动，
     * 于是<b>操作被静默记到上一次选中的 SKU 上</b>——而流水是 append-only 的，落错了只能再开反向流水。
     * 改成普通表单字段后，这一整类问题不存在，也不再需要任何 JS。
     */
    @PostMapping("/admin/shop/inventory/purchase")
    @PreAuthorize(EDIT_AUTH)
    public String receivePurchase(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long skuId,
            @RequestParam long qty,
            @RequestParam String purchaseNo,
            @RequestParam(required = false) String supplier,
            @RequestParam(required = false) Long costPrice,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate inboundDate,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            requireCostEdit(admin);
            movements.receivePurchase(skuId, qty, purchaseNo, supplier, costPrice, inboundDate,
                    admin.getAdminAccountId());
            return done(admin, skuId, msg.get("admin.flash.inventory.purchaseIn"), model);
        }
        try {
            requireCostEdit(admin);
            movements.receivePurchase(skuId, qty, purchaseNo, supplier, costPrice, inboundDate,
                    admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.inventory.purchaseIn"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_LIST;
    }

    /** 🔒 服务端独立再判一次——页面禁用页签只是第一层，看源码可绕过。 */
    private static void requireCostEdit(AdminUserDetails admin) {
        if (!has(admin, AdminPermissions.SHOP_COST_EDIT)) {
            throw AppException.forbidden("采购入库需要「编辑进货价」权限：入库单的进货单价不允许留空（S-9）").code("admin.err.inventory.costEditRequired");
        }
    }

    @PostMapping("/admin/shop/inventory/return-inbound")
    @PreAuthorize(EDIT_AUTH)
    public String receiveReturn(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long skuId,
            @RequestParam long qty,
            @RequestParam String originalOrderNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate inboundDate,
            HxRequest hx, Model model, RedirectAttributes ra) {
        // 单价由系统取该 SKU 最近一次采购入库单价（S-9）→ 不需要 cost_edit
        if (hx.isHtmx()) {
            movements.receiveReturn(skuId, qty, originalOrderNo, inboundDate,
                    admin.getAdminAccountId());
            return done(admin, skuId, msg.get("admin.flash.inventory.returnIn"), model);
        }
        try {
            movements.receiveReturn(skuId, qty, originalOrderNo, inboundDate,
                    admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.inventory.returnIn"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_LIST;
    }

    @PostMapping("/admin/shop/inventory/damage")
    @PreAuthorize(EDIT_AUTH)
    public String writeOff(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long skuId,
            @RequestParam long qty,
            @RequestParam String reason,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            movements.writeOff(skuId, qty, reason, admin.getAdminAccountId());
            return done(admin, skuId, msg.get("admin.flash.inventory.damage"), model);
        }
        try {
            movements.writeOff(skuId, qty, reason, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.inventory.damage"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_LIST;
    }

    @PostMapping("/admin/shop/inventory/stocktake")
    @PreAuthorize(EDIT_AUTH)
    public String stocktake(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long skuId,
            @RequestParam long countedActual,
            @RequestParam String reason,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            movements.stocktake(skuId, countedActual, reason, admin.getAdminAccountId());
            return done(admin, skuId, msg.get("admin.flash.inventory.stocktake"), model);
        }
        try {
            movements.stocktake(skuId, countedActual, reason, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.inventory.stocktake"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_LIST;
    }

    /**
     * 四个操作成功后的统一响应（AC2「提交 htmx 局部刷新抽屉与列表行」）。
     *
     * <p>主 swap 换<b>抽屉体</b>（表单的 {@code hx-target} 就是它）：流水摘要里立刻多出刚落的那一笔，
     * 运营在同一个抽屉里连着做第二笔时看到的是已经更新过的数。oob 换<b>被操作的那一行</b>与<b>摘要条</b>。
     *
     * <p>🔴 <b>只换那一行，不整表重拉</b>：列表按 {@code productId, id} 升序（与库存无关），
     * 库存变化不会让行换位置；整表重拉只会把运营刚翻到的第 3 页跳回第 1 页。
     * 但<b>摘要条必须换</b> —— 售罄数 / 低库存数 / 锁定合计是全表聚合，这一笔可能让别的格子也变。
     *
     * <p>🔴 <b>抽屉不自动关</b>（UI 稿 10-6）：库存操作常是连着几笔（入库完接着盘点），
     * 关掉等于每笔都要重新点开那一行。对象消失类动作才发 {@code admin:drawer-close}，库存不是。
     */
    private String done(AdminUserDetails admin, long skuId, String message, Model model) {
        populateDrawer(admin, skuId, model);
        model.addAttribute("summary", view.summary());
        model.addAttribute("message", message);
        return "admin/fragments/drawer-shop-inventory :: done";
    }

    // ---------- 内部 ----------

    /**
     * 权限判定。
     *
     * <p>⚠️ 与 {@code AdminShopProductController.has} 同款——那边是 {@code private static}，跨类不可
     * 调用。<b>有意重复这 8 行，而不是抽公共工具类</b>：抽取会改动 Story 1.3 处于 {@code review}
     * 的文件，属计划外的共享改动。
     */
    private static boolean has(AdminUserDetails admin, String permission) {
        if (admin == null) {
            return false;
        }
        for (GrantedAuthority a : admin.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority()) || permission.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
