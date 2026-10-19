package com.tailtopia.admin.gift.web;

import com.tailtopia.admin.gift.service.AdminUserGiftsService;
import com.tailtopia.admin.shared.web.HxRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户抽屉「收送礼记录」页签的胶囊切换与翻页（V1.4.0 ③ gift Story 3.2 · AC5.6）。只读 GET。
 *
 * <p>🔴 <b>只换本页签</b>：回 {@code drawer-users-gifts :: panel(visible=true)}，htmx 以 {@code outerHTML} 换掉
 * {@code #user-gifts-panel}——不整体重渲染抽屉（那会把页签切回「基本信息」，而地基页签头不许改）。
 *
 * <p>🛡 门 = 用户抽屉的 {@code user.view}（与 {@code AdminUserController.AUTH} 逐字一致；地基 AC5.5「沿用抽屉现有权限」，
 * 同「已购解锁」页签），不要求 {@code gift.order_view}（Story 3.2「待确认」1）。
 */
@Controller
public class AdminUserGiftsController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('user.view')";

    private final AdminUserGiftsService gifts;

    public AdminUserGiftsController(AdminUserGiftsService gifts) {
        this.gifts = gifts;
    }

    /** {@code dir} 宽松解析（缺省 / 不认识 = {@code sent}）；非 htmx 直达 → 回用户列表并自动开该抽屉。 */
    @GetMapping("/admin/users/{userId}/gifts")
    @PreAuthorize(AUTH)
    public String tab(@PathVariable long userId,
            @RequestParam(value = "dir", required = false) String dir,
            @RequestParam(value = "page", defaultValue = "0") int page,
            HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/users?open=" + userId;
        }
        model.addAttribute("giftsTab", gifts.page(userId, dir, Math.max(page, 0)));
        return "admin/fragments/drawer-users-gifts :: panel(visible=true)";
    }
}
