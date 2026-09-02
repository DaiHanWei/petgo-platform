package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.seed.service.AdminSeedImageService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shop.dto.ShopProductForm;
import com.tailtopia.admin.shop.dto.ShopSkuForm;
import com.tailtopia.admin.shop.service.AdminShopListingService;
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
import com.tailtopia.shop.service.ShopImageUrlResolver;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
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
    private final AdminShopListingService listing;
    private final ShopProductRepository products;
    private final ShopSkuRepository skus;
    private final InventoryService inventory;

    /** 图片直传。🔴 复用内容侧那条既有链路（白名单 jpeg/png/webp、≤10MB、走 OSS 公开桶），
        不新建基建 —— 差别只在 folder 参数。 */
    private final AdminSeedImageService images;

    /** objectKey → 公开 URL。编辑态要把库里存的 key 还原成缩略图才看得见。 */
    private final ShopImageUrlResolver imageUrls;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminShopProductController(AdminShopProductService service,
            AdminShopListingService listing, ShopProductRepository products,
            ShopSkuRepository skus, InventoryService inventory,
            AdminSeedImageService images, ShopImageUrlResolver imageUrls,
            Messages msg) {
        this.service = service;
        this.listing = listing;
        this.products = products;
        this.skus = skus;
        this.inventory = inventory;
        this.images = images;
        this.imageUrls = imageUrls;
        this.msg = msg;
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
        // 🔴 告警条与「阻止上架」共用 AdminShopListingService 的同一口径，不在此另算一遍
        model.addAttribute("activeSkuCount", listing.activeSkuCount());
        model.addAttribute("skuCap", listing.skuCap());
        model.addAttribute("skuCapReached", listing.atOrOverCap());
        return "admin/shop-products";
    }

    // ---------- 表单 ----------

    @GetMapping("/admin/shop/products/new")
    @PreAuthorize(EDIT_AUTH)
    public String createForm(Model model) {
        model.addAttribute("form", new ShopProductForm());
        model.addAttribute("productId", null);
        // 新建态没有已存图；给空列表而不是不放，模板那边就不用两套写法。
        model.addAttribute("existingImages", List.of());
        putEnums(model);
        model.addAttribute("active", "shopProducts");
        return "admin/shop-product-form";
    }

    @GetMapping("/admin/shop/products/{id}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, Model model) {
        ShopProduct p = products.findById(id)
                .orElseThrow(() -> AppException.notFound("商品不存在").code("admin.err.product.notFound"));
        List<ShopSku> ss = skus.findByProductIdOrderByIdAsc(id);
        model.addAttribute("form", toForm(p));
        model.addAttribute("productId", id);
        // 编辑态回填：库里存的是 objectKey，直接塞进 <img> 是显示不出来的。
        // ⚠️ 顺序必须是「主图在前 + 图集依次」—— 页面把第一张当封面，写回时也按这个顺序拆回
        //    mainImageKey / galleryKeysRaw。顺序错了会把图集第一张变成主图。
        // ⚠️ 用空串而不是 null 表示"没有尺寸"：Map.of 不接受 null 值，
        //    且模板里 data-w="" 与缺属性对 JS 是等价的（getAttribute 取到空串即视为无）。
        List<Map<String, String>> existing = new java.util.ArrayList<>();
        if (p.getMainImageKey() != null && !p.getMainImageKey().isBlank()) {
            existing.add(Map.of("key", p.getMainImageKey(),
                    "url", String.valueOf(imageUrls.publicUrl(p.getMainImageKey())),
                    "w", p.getMainImageW() == null ? "" : String.valueOf(p.getMainImageW()),
                    "h", p.getMainImageH() == null ? "" : String.valueOf(p.getMainImageH())));
        }
        // 🔴 图集不存尺寸：列表页只渲染主图，详情页轮播是等高容器，用不上比例。
        for (String k : p.getGalleryKeys()) {
            if (k != null && !k.isBlank()) {
                existing.add(Map.of("key", k, "url", String.valueOf(imageUrls.publicUrl(k)),
                        "w", "", "h", ""));
            }
        }
        model.addAttribute("existingImages", existing);
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

    /**
     * 商品图直传（2026-08-27）。
     *
     * <p><b>此前运营只能填 objectKey</b> —— 意味着得先去别处把图传上对象存储、抄下 key、
     * 再粘回这个框。内容侧（Story 12.2）早已把这一步收回后台，商品这边一直没跟上。
     *
     * <p>🔴 <b>返回体里页面真正要用的是 {@code objectKey} 而不是 {@code url}</b>：
     * 商品图入库存的是 key（{@code ShopProductSummaryView} 的契约写明「是 objectKey 不是 URL」），
     * URL 只用于当场显示缩略图。两者都在 {@link com.tailtopia.admin.seed.dto.UploadedImage} 里。
     *
     * <p>⚠️ 一次一张、错误一律 400 + {@code {"error":...}}，与内容侧同构 ——
     * 前端那个上传控件是共用的，回包形状不一致它就得分叉。
     */
    @PostMapping("/admin/shop/products/images")
    @PreAuthorize(EDIT_AUTH)
    @ResponseBody
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(images.upload(file, "shop-product"));
        } catch (AppException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // 对象存储未配置 / 凭证异常：回人话，不把 500 甩到运营脸上。
            return ResponseEntity.badRequest()
                    .body(Map.of("error", msg.get("admin.shop.form.uploadFailed")));
        }
    }

    // ---------- 写入 ----------

    // 🔴 写端点一律本地 catch AppException：GlobalExceptionHandler 是 @RestControllerAdvice，
    //    不 catch 就会把 RFC 9457 裸 JSON 甩给运营，而不是回到页面看到一条提示。
    //    仓库里 17 个既有 admin 控制器全部如此，本文件先前是例外（Story 1.5 补齐）。

    @PostMapping("/admin/shop/products")
    @PreAuthorize(EDIT_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @ModelAttribute("form") ShopProductForm form, RedirectAttributes ra) {
        try {
            ShopProduct p = service.create(form, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.product.created"));
            return "redirect:/admin/shop/products/" + p.getId();
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
            return "redirect:/admin/shop/products/new";
        }
    }

    @PostMapping("/admin/shop/products/{id}")
    @PreAuthorize(EDIT_AUTH)
    public String update(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @ModelAttribute("form") ShopProductForm form, RedirectAttributes ra) {
        try {
            service.update(id, form, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.product.updated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/products/" + id;
    }

    // 🔴 路径变量叫 {productId} 而不是 {id} —— 这不是命名洁癖，是**必须的**：
    //    Spring 的 ExtendedServletRequestDataBinder 会把 URI 模板变量一并绑进 @ModelAttribute
    //    （仅当同名请求参数缺席时）。若这里叫 {id}，商品 id 就会被绑进 ShopSkuForm.id，
    //    于是「新建规格」永远走成「更新规格」分支：
    //      · 找不到该 id 的 SKU → 恒报「规格不存在」，运营一个规格都建不出来；
    //      · 🔴 更糟：两张表都是 BIGSERIAL，新店里 shop_skus.id 与 shop_products.id 撞号很常见，
    //        一旦撞上且那个 SKU 恰属本商品，就会【静默覆盖既有规格】而不是新建。
    //    页面上之所以没出事，只是因为模板恰好渲染了一个空的 <input name="id"/>（请求参数在场
    //    → URI 变量被跳过）。**把正确性寄托在「模板碰巧有那一行」上不成立**：
    //    删掉那行、或换个调用方（curl / 脚本）就复现。2026-08-18 补 L1 时抓到。
    @PostMapping("/admin/shop/products/{productId}/skus")
    @PreAuthorize(EDIT_AUTH)
    public String upsertSku(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long productId, @ModelAttribute ShopSkuForm form,
            RedirectAttributes ra) {
        try {
            // 🔒 进货价与 SKU 基础字段分开处理：无 cost_edit 权限时表单里的该值被直接丢弃
            Long submittedCost = form.getCostPrice();
            form.setCostPrice(null);
            ShopSku sku = service.upsertSku(productId, form, admin.getAdminAccountId());
            if (submittedCost != null && has(admin, AdminPermissions.SHOP_COST_EDIT)) {
                service.updateCostPrice(sku.getId(), submittedCost, admin.getAdminAccountId());
            }
            ra.addFlashAttribute("notice", msg.get("admin.flash.product.skuSaved"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/products/" + productId;
    }

    // ---------- 上下架（Story 1.5，AB-10D） ----------

    @PostMapping("/admin/shop/products/{id}/list")
    @PreAuthorize(EDIT_AUTH)
    public String listProduct(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, RedirectAttributes ra) {
        try {
            listing.list(id, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.product.activated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/products";
    }

    @PostMapping("/admin/shop/products/{id}/delist")
    @PreAuthorize(EDIT_AUTH)
    public String delistProduct(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, RedirectAttributes ra) {
        try {
            listing.delist(id, admin.getAdminAccountId());
            // ⚠️ 下架 ≠ 立即停止发货：已下单未支付的订单照常履约（SPEC-7 口径）
            ra.addFlashAttribute("notice", msg.get("admin.flash.product.deactivated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/products";
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
        // getGalleryKeys() 保证非 null（见其 javadoc / D-20），此处不再判空。
        f.setGalleryKeysRaw(String.join("\n", p.getGalleryKeys()));
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
