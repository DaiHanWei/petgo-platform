package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shop.dto.ShopProductForm;
import com.tailtopia.admin.shop.dto.ShopSkuForm;
import com.tailtopia.admin.shop.service.AdminShopProductService;
import com.tailtopia.shop.domain.AgeStage;
import com.tailtopia.shop.domain.BodySize;
import com.tailtopia.shop.domain.ProductCategory;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.domain.Species;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.service.InventoryService;
import com.tailtopia.shared.error.AppException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 商品与 SKU 后台维护（Story 1.3，AB-10A / AB-10B）。模块 10 的入口。
 *
 * <p>🔒 <b>进货价的权限门控在服务端，不在模板</b>：无 {@code shop.cost_view} 时
 * {@code costPrice} <b>根本不放进 model</b>——模板里用 {@code th:if} 隐藏可通过看源码绕过。
 * 写入侧同理：无 {@code shop.cost_edit} 时表单里的该字段被<b>直接丢弃</b>，不进 service。
 */
@Controller
public class AdminShopProductController {

    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.product_view') "
                    + "or hasAuthority('shop.product_edit')";
    private static final String EDIT_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.product_edit')";

    private final AdminShopProductService service;
    private final ShopProductRepository products;
    private final ShopSkuRepository skus;
    private final InventoryService inventory;

    public AdminShopProductController(AdminShopProductService service,
            ShopProductRepository products, ShopSkuRepository skus, InventoryService inventory) {
        this.service = service;
        this.products = products;
        this.skus = skus;
        this.inventory = inventory;
    }

    // ---------- 列表 ----------

    @GetMapping("/admin/shop/products")
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(required = false) ProductCategory category,
            @RequestParam(required = false) Boolean active, Model model) {
        List<ShopProduct> rows = products.findAll().stream()
                .filter(p -> category == null || p.getCategory() == category)
                .filter(p -> active == null || p.isActive() == active)
                .sorted((a, b) -> {
                    int c = Integer.compare(b.getSortWeight(), a.getSortWeight());
                    return c != 0 ? c : Long.compare(b.getId(), a.getId());
                })
                .toList();
        Map<Long, Integer> skuCount = new LinkedHashMap<>();
        Map<Long, Long> minPrice = new LinkedHashMap<>();
        for (ShopProduct p : rows) {
            List<ShopSku> ss = skus.findByProductIdOrderByIdAsc(p.getId());
            skuCount.put(p.getId(), ss.size());
            ss.stream().mapToLong(ShopSku::getPrice).min()
                    .ifPresent(v -> minPrice.put(p.getId(), v));
        }
        model.addAttribute("active", "shopProducts");
        model.addAttribute("products", rows);
        model.addAttribute("skuCount", skuCount);
        model.addAttribute("minPrice", minPrice);
        model.addAttribute("categories", ProductCategory.values());
        model.addAttribute("filterCategory", category);
        model.addAttribute("filterActive", active);
        model.addAttribute("canEdit", has(admin, AdminPermissions.SHOP_PRODUCT_EDIT));
        return "admin/shop-products";
    }

    // ---------- 表单 ----------

    @GetMapping("/admin/shop/products/new")
    @PreAuthorize(EDIT_AUTH)
    public String createForm(Model model) {
        model.addAttribute("form", new ShopProductForm());
        model.addAttribute("productId", null);
        putEnums(model);
        model.addAttribute("active", "shopProducts");
        return "admin/shop-product-form";
    }

    @GetMapping("/admin/shop/products/{id}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, Model model) {
        ShopProduct p = products.findById(id)
                .orElseThrow(() -> AppException.notFound("商品不存在"));
        List<ShopSku> ss = skus.findByProductIdOrderByIdAsc(id);
        model.addAttribute("form", toForm(p));
        model.addAttribute("productId", id);
        model.addAttribute("product", p);
        model.addAttribute("skus", ss);
        model.addAttribute("availableBySku", inventory.availableBySkuId(
                ss.stream().map(ShopSku::getId).toList()));
        model.addAttribute("feedingWarning",
                service.feedingGuideWarning(p.getCategory(), p.getFeedingGuide()));

        // 🔒 进货价：有权限才放进 model —— 无权限时模板拿不到这个 map，不是靠 th:if 隐藏
        boolean canViewCost = has(admin, AdminPermissions.SHOP_COST_VIEW);
        model.addAttribute("canViewCost", canViewCost);
        model.addAttribute("canEditCost", has(admin, AdminPermissions.SHOP_COST_EDIT));
        if (canViewCost) {
            Map<Long, Long> costs = new LinkedHashMap<>();
            for (ShopSku s : ss) {
                costs.put(s.getId(), s.getCostPrice());
            }
            model.addAttribute("costBySku", costs);
        }
        putEnums(model);
        model.addAttribute("active", "shopProducts");
        return "admin/shop-product-form";
    }

    // ---------- 写入 ----------

    @PostMapping("/admin/shop/products")
    @PreAuthorize(EDIT_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @ModelAttribute("form") ShopProductForm form, RedirectAttributes ra) {
        ShopProduct p = service.create(form, admin.getAdminAccountId());
        ra.addFlashAttribute("flash", "商品已创建");
        return "redirect:/admin/shop/products/" + p.getId();
    }

    @PostMapping("/admin/shop/products/{id}")
    @PreAuthorize(EDIT_AUTH)
    public String update(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @ModelAttribute("form") ShopProductForm form, RedirectAttributes ra) {
        service.update(id, form, admin.getAdminAccountId());
        ra.addFlashAttribute("flash", "商品已更新");
        return "redirect:/admin/shop/products/" + id;
    }

    @PostMapping("/admin/shop/products/{id}/skus")
    @PreAuthorize(EDIT_AUTH)
    public String upsertSku(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, @ModelAttribute ShopSkuForm form, RedirectAttributes ra) {
        // 🔒 进货价与 SKU 基础字段分开处理：无 cost_edit 权限时表单里的该值被直接丢弃
        Long submittedCost = form.getCostPrice();
        form.setCostPrice(null);
        ShopSku sku = service.upsertSku(id, form, admin.getAdminAccountId());
        if (submittedCost != null && has(admin, AdminPermissions.SHOP_COST_EDIT)) {
            service.updateCostPrice(sku.getId(), submittedCost, admin.getAdminAccountId());
        }
        ra.addFlashAttribute("flash", "规格已保存");
        return "redirect:/admin/shop/products/" + id;
    }

    // ---------- helpers ----------

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

    private static void putEnums(Model model) {
        model.addAttribute("categories", ProductCategory.values());
        model.addAttribute("speciesList", Species.values());
        model.addAttribute("bodySizes", BodySize.values());
        model.addAttribute("ageStages", AgeStage.values());
        model.addAttribute("returnPolicies", ReturnPolicy.values());
    }

    private static ShopProductForm toForm(ShopProduct p) {
        ShopProductForm f = new ShopProductForm();
        f.setName(p.getName());
        f.setBrand(p.getBrand());
        f.setCategory(p.getCategory());
        f.setMainImageKey(p.getMainImageKey());
        f.setGalleryKeysRaw(p.getGalleryKeys() == null ? "" : String.join("\n", p.getGalleryKeys()));
        f.setSpecies(p.getSpecies());
        f.setBodySize(p.getBodySize());
        f.setAgeStage(p.getAgeStage());
        f.setDetailHtml(p.getDetailHtml());
        f.setShelfLifeNote(p.getShelfLifeNote());
        f.setReturnPolicy(p.getReturnPolicy());
        f.setSortWeight(p.getSortWeight());
        if (p.getFeedingGuide() != null) {
            f.setFeedWeightMinKg(p.getFeedingGuide().stream().map(e -> e.weightMinKg()).toList());
            f.setFeedWeightMaxKg(p.getFeedingGuide().stream().map(e -> e.weightMaxKg()).toList());
            f.setFeedGramsPerDay(p.getFeedingGuide().stream().map(e -> e.gramsPerDay()).toList());
        }
        return f;
    }
}
