package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shop.dto.InventoryRowView;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.InventoryMovement;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryMovementService;
import com.tailtopia.shop.service.InventoryService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    private final InventoryMovementService movements;
    private final InventoryService inventory;
    private final ShopProductRepository products;
    private final ShopSkuRepository skus;

    public AdminShopInventoryController(InventoryMovementService movements,
            InventoryService inventory, ShopProductRepository products, ShopSkuRepository skus) {
        this.movements = movements;
        this.inventory = inventory;
        this.products = products;
        this.skus = skus;
    }

    // ---------- 列表：三个数不合并显示 ----------

    @GetMapping("/admin/shop/inventory")
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin, Model model) {
        List<ShopSku> allSkus = skus.findAll();
        Map<Long, String> productNames = products.findAll().stream()
                .collect(Collectors.toMap(ShopProduct::getId, ShopProduct::getName, (a, b) -> a));

        List<Long> skuIds = allSkus.stream().map(ShopSku::getId).toList();
        Map<Long, Long> availableBySku = inventory.availableBySkuId(skuIds);
        Map<Long, Long> actualBySku = actualBySkuId(skuIds);

        List<InventoryRowView> rows = new ArrayList<>();
        for (ShopSku sku : allSkus) {
            long actual = actualBySku.getOrDefault(sku.getId(), 0L);
            long available = availableBySku.getOrDefault(sku.getId(), 0L);
            rows.add(InventoryRowView.of(sku.getId(), sku.getPublicToken(),
                    productNames.getOrDefault(sku.getProductId(), "-"), sku.getSpecName(),
                    actual, actual - available, inventory.statusOf(available)));
        }

        model.addAttribute("rows", rows);
        model.addAttribute("canEdit", has(admin, AdminPermissions.SHOP_INVENTORY_EDIT));
        // 🔒 采购入库要填进货单价 → 无 cost_edit 则连入库入口都不渲染（服务端另有独立判定）
        model.addAttribute("canPurchase", has(admin, AdminPermissions.SHOP_INVENTORY_EDIT)
                && has(admin, AdminPermissions.SHOP_COST_EDIT));
        model.addAttribute("canViewCost", has(admin, AdminPermissions.SHOP_COST_VIEW));
        model.addAttribute("active", "shopInventory");
        return "admin/shop-inventory";
    }

    // ---------- 流水（含前后值，可审计） ----------

    @GetMapping("/admin/shop/inventory/{skuId}/movements")
    @PreAuthorize(VIEW_AUTH)
    public String movements(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long skuId, Model model) {
        ShopSku sku = skus.findById(skuId)
                .orElseThrow(() -> AppException.notFound("SKU 不存在"));
        boolean canViewCost = has(admin, AdminPermissions.SHOP_COST_VIEW);

        List<InventoryMovement> rows = movements.recentMovements(skuId, MOVEMENT_PAGE_SIZE);
        model.addAttribute("sku", sku);
        model.addAttribute("movements", rows);
        model.addAttribute("canViewCost", canViewCost);
        // 🔒 无 cost_view 时进货单价【根本不进 model】，不是靠模板隐藏
        model.addAttribute("costByMovementId", canViewCost
                ? rows.stream().filter(m -> m.getCostPrice() != null)
                        .collect(Collectors.toMap(InventoryMovement::getId,
                                InventoryMovement::getCostPrice, (a, b) -> a))
                : Map.of());
        model.addAttribute("active", "shopInventory");
        return "admin/shop-inventory-movements";
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
            RedirectAttributes ra) {
        try {
            // 🔒 服务端独立再判一次——页面不渲染入口只是第一层，看源码可绕过
            if (!has(admin, AdminPermissions.SHOP_COST_EDIT)) {
                throw AppException.forbidden(
                        "采购入库需要「编辑进货价」权限：入库单的进货单价不允许留空（S-9）");
            }
            movements.receivePurchase(skuId, qty, purchaseNo, supplier, costPrice, inboundDate,
                    admin.getAdminAccountId());
            ra.addFlashAttribute("notice", "采购入库已登记");
        } catch (AppException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shop/inventory";
    }

    @PostMapping("/admin/shop/inventory/return-inbound")
    @PreAuthorize(EDIT_AUTH)
    public String receiveReturn(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long skuId,
            @RequestParam long qty,
            @RequestParam String originalOrderNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate inboundDate,
            RedirectAttributes ra) {
        try {
            // 单价由系统取该 SKU 最近一次采购入库单价（S-9）→ 不需要 cost_edit
            movements.receiveReturn(skuId, qty, originalOrderNo, inboundDate,
                    admin.getAdminAccountId());
            ra.addFlashAttribute("notice", "退货入库已登记（退货入库批次）");
        } catch (AppException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shop/inventory";
    }

    @PostMapping("/admin/shop/inventory/damage")
    @PreAuthorize(EDIT_AUTH)
    public String writeOff(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long skuId,
            @RequestParam long qty,
            @RequestParam String reason,
            RedirectAttributes ra) {
        try {
            movements.writeOff(skuId, qty, reason, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", "报损已登记");
        } catch (AppException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shop/inventory";
    }

    @PostMapping("/admin/shop/inventory/stocktake")
    @PreAuthorize(EDIT_AUTH)
    public String stocktake(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long skuId,
            @RequestParam long countedActual,
            @RequestParam String reason,
            RedirectAttributes ra) {
        try {
            movements.stocktake(skuId, countedActual, reason, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", "盘点调整已登记");
        } catch (AppException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/shop/inventory";
    }

    // ---------- 内部 ----------

    /** 批量取实际库存，避免列表 N+1（与 {@code availableBySkuId} 同一次数据来源口径）。 */
    private Map<Long, Long> actualBySkuId(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return inventory.rowsBySkuId(skuIds).stream()
                .collect(Collectors.toMap(r -> r.getSkuId(), r -> r.getActual(), (a, b) -> a));
    }

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
