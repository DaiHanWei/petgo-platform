package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.seed.service.AdminSeedImageService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shop.dto.ShopBannerForm;
import com.tailtopia.admin.shop.service.AdminShopBannerService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.shop.domain.ShopBanner;
import com.tailtopia.shop.repository.ShopBannerRepository;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Toko 顶部 banner 后台维护（2026-08-27）。
 *
 * <p>🔒 <b>权限复用商品的 {@code shop.product_view / product_edit}</b>，不另立 banner 码：
 * banner 与商品同属"商品运营"这件事，而新增权限码会波及权限分配界面与角色模板 ——
 * 为一个页面引入那些改动，收益不抵风险。真需要分权时再拆。
 *
 * <p>🔴 <b>同一时间只展示一张</b>：本页可以配多条，但 App 只取「已上架 + 权重最高」的那条。
 * 列表按取用顺序排列，第一条已上架的就是用户会看到的那张 —— 页面上会明确标出来，
 * 否则运营配了三条却不知道哪条生效。
 */
@Controller
public class AdminShopBannerController {

    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.product_view') "
                    + "or hasAuthority('shop.product_edit')";
    private static final String EDIT_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('shop.product_edit')";

    private final AdminShopBannerService service;
    private final ShopBannerRepository banners;
    private final ShopImageUrlResolver imageUrls;
    private final AdminSeedImageService images;
    private final Messages msg;

    public AdminShopBannerController(AdminShopBannerService service,
            ShopBannerRepository banners, ShopImageUrlResolver imageUrls,
            AdminSeedImageService images, Messages msg) {
        this.service = service;
        this.banners = banners;
        this.imageUrls = imageUrls;
        this.images = images;
        this.msg = msg;
    }

    @GetMapping("/admin/shop/banners")
    @PreAuthorize(VIEW_AUTH)
    public String list(Model model) {
        List<ShopBanner> rows = banners.findAllByOrderBySortWeightDescIdDesc();
        // 🔴 标出"当前生效"的那一条：列表里可能有多条 active，但只有第一条会被 App 取到。
        //    不标的话运营会以为所有 active 的都在轮播 —— 而本版本根本没有轮播。
        Long liveId = rows.stream().filter(ShopBanner::isActive)
                .map(ShopBanner::getId).findFirst().orElse(null);

        List<Map<String, Object>> view = new ArrayList<>();
        for (ShopBanner b : rows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("imageKey", b.getImageKey());
            // CDN 未配时为 null —— 模板据此显示"URL 拼不出来"而不是渲染一张裂图。
            m.put("imageUrl", imageUrls.publicUrl(b.getImageKey()));
            m.put("w", b.getImageW());
            m.put("h", b.getImageH());
            m.put("active", b.isActive());
            m.put("sortWeight", b.getSortWeight());
            m.put("live", b.getId().equals(liveId));
            view.add(m);
        }
        model.addAttribute("banners", view);
        model.addAttribute("hasLive", liveId != null);
        model.addAttribute("form", new ShopBannerForm());
        model.addAttribute("active", "shopBanners");
        return "admin/shop-banners";
    }

    /** banner 图直传。与商品图同构（同一条上传链路，只换 folder）。 */
    @PostMapping("/admin/shop/banners/images")
    @PreAuthorize(EDIT_AUTH)
    @ResponseBody
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(images.upload(file, "shop-banner"));
        } catch (AppException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", msg.get("admin.shop.form.uploadFailed")));
        }
    }

    // ---------- 写入 ----------
    // 🔴 写端点一律本地 catch AppException：GlobalExceptionHandler 是 @RestControllerAdvice，
    //    不 catch 就会把 RFC 9457 裸 JSON 甩给运营，而不是回到页面看到一条提示。

    @PostMapping("/admin/shop/banners")
    @PreAuthorize(EDIT_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @ModelAttribute("form") ShopBannerForm form, RedirectAttributes ra) {
        try {
            service.create(form, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.banner.created"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/banners";
    }

    @PostMapping("/admin/shop/banners/{id}")
    @PreAuthorize(EDIT_AUTH)
    public String update(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @ModelAttribute("form") ShopBannerForm form, RedirectAttributes ra) {
        try {
            service.update(id, form, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.banner.updated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/banners";
    }

    @PostMapping("/admin/shop/banners/{id}/activate")
    @PreAuthorize(EDIT_AUTH)
    public String activate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes ra) {
        try {
            service.activate(id, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.banner.activated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/banners";
    }

    @PostMapping("/admin/shop/banners/{id}/deactivate")
    @PreAuthorize(EDIT_AUTH)
    public String deactivate(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, RedirectAttributes ra) {
        try {
            service.deactivate(id, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.banner.deactivated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/banners";
    }

    @PostMapping("/admin/shop/banners/{id}/delete")
    @PreAuthorize(EDIT_AUTH)
    public String delete(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes ra) {
        try {
            service.delete(id, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.banner.deleted"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/banners";
    }
}
