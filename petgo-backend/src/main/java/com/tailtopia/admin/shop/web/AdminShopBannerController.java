package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.seed.service.AdminSeedImageService;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shop.dto.ShopBannerForm;
import com.tailtopia.admin.shop.service.AdminShopBannerService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.shop.domain.ShopBanner;
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
import jakarta.servlet.http.HttpServletResponse;
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
    private final ShopImageUrlResolver imageUrls;
    private final AdminSeedImageService images;
    private final Messages msg;

    public AdminShopBannerController(AdminShopBannerService service,
            ShopImageUrlResolver imageUrls, AdminSeedImageService images, Messages msg) {
        this.service = service;
        this.imageUrls = imageUrls;
        this.images = images;
        this.msg = msg;
    }

    /**
     * Banner 列表（V1.3.0 Story 10.3 AC3 / AC4：模板 B + 抽屉）。
     *
     * <p>🔴 <b>抽屉取数复用这同一条 mapping，不新开端点</b>（AB-19A）：
     * {@code ?create=1} + {@code HX-Request} 返新建表单片段，{@code ?open=<id>} 返编辑表单片段，
     * 两者都不带的 htmx 请求返行片段，非 htmx 返整页。模板 B 的惯例是
     * {@code GET …/{id}/drawer}，但那是新端点 —— 与 10.1 / 10.2 同款处置。
     */
    @GetMapping("/admin/shop/banners")
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "open", required = false) Long open,
            @RequestParam(value = "create", required = false) String create,
            HxRequest hx, Model model) {
        model.addAttribute("active", "shopBanners");
        // 🔒 只读账号不给写入口：没有这道门，他能打开抽屉、填完提交才收到 403 —— 那是一次白填。
        //    服务端的 @PreAuthorize 照旧兜底（模板隐藏可以看源码绕过）。
        model.addAttribute("canEdit", has(admin, AdminPermissions.SHOP_PRODUCT_EDIT));
        if (hx.isHtmx() && create != null) {
            model.addAttribute("form", new ShopBannerForm());
            model.addAttribute("bannerId", null);
            model.addAttribute("existingImage", null);
            return "admin/fragments/drawer-shop-banner :: form";
        }
        if (hx.isHtmx() && open != null) {
            populateEditForm(open, model);
            return "admin/fragments/drawer-shop-banner :: form";
        }
        populateList(model);
        model.addAttribute("open", open);
        model.addAttribute("openCreate", create != null);
        return hx.isHtmx() ? "admin/fragments/shop-banners-list :: rows" : "admin/shop-banners";
    }

    /** 编辑态回填：库里存的是 objectKey，直接塞进 {@code <img>} 是显示不出来的。 */
    private void populateEditForm(long id, Model model) {
        ShopBanner b = service.require(id);
        ShopBannerForm f = new ShopBannerForm();
        f.setImageKey(b.getImageKey());
        f.setImageW(b.getImageW());
        f.setImageH(b.getImageH());
        f.setSortWeight(b.getSortWeight());
        model.addAttribute("form", f);
        model.addAttribute("bannerId", id);
        // ⚠️ CDN 未配时 publicUrl 返 null —— 不能 String.valueOf 成字符串 "null"，
        //    那会在抽屉里渲染出一张 src="null" 的裂图。列表片段对这种情况是显示「URL 拼不出来」的，
        //    抽屉这边同口径：拼不出 URL 就当没有已存图（key 仍在隐藏字段里，不换图照样保留）。
        String url = b.getImageKey() == null || b.getImageKey().isBlank()
                ? null : imageUrls.publicUrl(b.getImageKey());
        model.addAttribute("existingImage", url == null ? null
                : Map.of("key", b.getImageKey(), "url", url,
                        "w", b.getImageW() == null ? "" : String.valueOf(b.getImageW()),
                        "h", b.getImageH() == null ? "" : String.valueOf(b.getImageH())));
    }

    /** ⚠️ 与既有 shop 控制器同款（各自 {@code private static}，跨类不可调用）。 */
    private static boolean has(AdminUserDetails admin, String permission) {
        if (admin == null) {
            return false;
        }
        for (org.springframework.security.core.GrantedAuthority a : admin.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority()) || permission.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private void populateList(Model model) {
        // 🔴 「生效中」那一条问的是 App 端自己那条查询（见 AdminShopBannerService#liveId）——
        //    后台再写一遍「取第一条 active」就是第二份判据，将来 App 改了取图规则这里不会跟着变，
        //    而界面上完全看不出来：运营以为 A 在投，用户看到的是 B。
        Long liveId = service.liveId();
        List<Map<String, Object>> view = new ArrayList<>();
        for (ShopBanner b : service.all()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("imageKey", b.getImageKey());
            // CDN 未配时为 null —— 模板据此显示"URL 拼不出来"而不是渲染一张裂图。
            m.put("imageUrl", imageUrls.publicUrl(b.getImageKey()));
            m.put("w", b.getImageW());
            m.put("h", b.getImageH());
            m.put("active", b.isActive());
            m.put("sortWeight", b.getSortWeight());
            m.put("state", AdminShopBannerService.stateOf(b, liveId));
            view.add(m);
        }
        model.addAttribute("banners", view);
        model.addAttribute("hasLive", liveId != null);
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
            @ModelAttribute("form") ShopBannerForm form, HxRequest hx, Model model,
            HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.create(form, admin.getAdminAccountId());
            return done(msg.get("admin.flash.banner.created"), model, response);
        }
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
            @ModelAttribute("form") ShopBannerForm form, HxRequest hx, Model model,
            HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.update(id, form, admin.getAdminAccountId());
            return done(msg.get("admin.flash.banner.updated"), model, response);
        }
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
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.activate(id, admin.getAdminAccountId());
            return done(msg.get("admin.flash.banner.activated"), model, response);
        }
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
    public String deactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.deactivate(id, admin.getAdminAccountId());
            return done(msg.get("admin.flash.banner.deactivated"), model, response);
        }
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
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.delete(id, admin.getAdminAccountId());
            return done(msg.get("admin.flash.banner.deleted"), model, response);
        }
        try {
            service.delete(id, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.banner.deleted"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/banners";
    }

    /**
     * 处置成功统一响应（AC4）：整表重拉 + toast + 关抽屉。
     *
     * <p>🔴 <b>整表重拉而不是换一行</b>：三档状态是**全表相对**的 —— 上架 / 下架 / 改权重
     * 任何一个动作都可能让「生效中」从 A 变成 B，而 B 那一行根本没被点过。
     * 只换被点的那行，另一行的徽标就停在旧状态，运营看到的是两条「生效中」或一条都没有。
     *
     * <p>新建 / 编辑是在抽屉里提交的，成功后发 {@code admin:drawer-close} 把它关掉
     * （上架 / 下架 / 删除本来就不开抽屉，多发一个事件无害）。
     */
    private String done(String message, Model model, HttpServletResponse response) {
        populateList(model);
        model.addAttribute("message", message);
        AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_CLOSE);
        return "admin/fragments/shop-banners-list :: done";
    }
}
