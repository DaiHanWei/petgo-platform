package com.tailtopia.admin.petslot.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.petslot.service.AdminPetSlotGrantService;
import com.tailtopia.admin.petslot.service.AdminPetSlotTabService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.profile.slot.AdminSlotGrantResult;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 用户抽屉「宠物与名额」页签的运营补发名额（V1.4.0 multi-pet Story 4.3 · AB-34.2 · UI 稿 L2–L4）。
 *
 * <p>页签内容随抽屉一次聚合（{@code AdminUserController.populateDrawer}），本类只有补发这一个写端点：
 * <ul>
 *   <li>htmx 成功：{@code HX-Retarget #user-petslots-panel} + {@code HX-Reswap outerHTML}，<b>只换本页签</b>（整体重渲染
 *       会把页签切回「基本信息」，而页签头不许改），新面板带新的一次性 {@code grantToken}；toast 走 {@code #admin-toast-host} oob；</li>
 *   <li>htmx 失败（422 / 404）：异常冒出，由 {@code AdminBusinessExceptionAdvice} 落进表单的 {@code hx-target}（{@code #user-drawer-err}），表单不清空；</li>
 *   <li>非 htmx：PRG 回 {@code /admin/users?open={userId}} + flash（照 {@code grantPawCoin}）。</li>
 * </ul>
 * 门控 {@code user.pet_slot_adjust}（AD-15，OPS_MANAGER 枚举预授）。
 */
@Controller
public class AdminPetSlotController {

    /** ⚠️ 表达式须与模板补发卡的 {@code sec:authorize} 逐字一致。 */
    static final String PET_SLOT_ADJUST_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.USER_PET_SLOT_ADJUST + "')";

    static final String PANEL_TARGET = "#user-petslots-panel";

    private final AdminPetSlotGrantService grants;
    private final AdminPetSlotTabService tabs;
    private final Messages msg;

    public AdminPetSlotController(AdminPetSlotGrantService grants, AdminPetSlotTabService tabs, Messages msg) {
        this.grants = grants;
        this.tabs = tabs;
        this.msg = msg;
    }

    @PostMapping("/admin/users/{userId}/pet-slots/grant")
    @PreAuthorize(PET_SLOT_ADJUST_AUTH)
    public String grant(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam(value = "days", required = false) String days,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "grantToken", required = false) String grantToken,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // toast 取实际补发结果的天数：同 token 重放（哪怕这次填了别的天数）报的是首次补发（code review #3）。
            AdminSlotGrantResult result = grants.grant(userId, parseDays(days), reason, grantToken,
                    admin.getAdminAccountId());
            populatePanel(userId, model);
            model.addAttribute("toast", msg.get("admin.flash.petSlot.granted", result.days()));
            response.setHeader(AdminFragmentResponses.HEADER_RETARGET, PANEL_TARGET);
            response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "outerHTML");
            return "admin/fragments/drawer-users-petslots :: afterGrant";
        }
        try {
            AdminSlotGrantResult result = grants.grant(userId, parseDays(days), reason, grantToken,
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.petSlot.granted", result.days()));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/users?open=" + userId;
    }

    /** 页签片段的两个 model 属性（与 {@code AdminUserController.populateDrawer} 同名）：每次渲染换一个新的幂等 token。 */
    private void populatePanel(long userId, Model model) {
        model.addAttribute("petSlotsTab", tabs.tab(userId));
        model.addAttribute("petSlotGrantToken", UUID.randomUUID().toString());
    }

    /** 天数：整数且 1–365，否则 422（非数字也走同一条文案，不让类型转换异常冒成 400）。 */
    static int parseDays(String raw) {
        int n;
        try {
            n = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw AppException.validation("补发天数须为 1–365 的整数").code("admin.err.petSlot.daysInvalid");
        }
        if (n < 1 || n > 365) {
            throw AppException.validation("补发天数须为 1–365 的整数").code("admin.err.petSlot.daysInvalid");
        }
        return n;
    }
}
