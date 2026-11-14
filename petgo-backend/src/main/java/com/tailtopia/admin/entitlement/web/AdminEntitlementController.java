package com.tailtopia.admin.entitlement.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.entitlement.service.AdminEntitlementGrantService;
import com.tailtopia.admin.entitlement.service.AdminUserEntitlementsService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.entitlement.domain.CreditType;
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
 * 用户抽屉「已购解锁」页签的运营补发免费次数（V1.5.0 gift-pack Story 5-2 · AC2.4，照 {@code AdminPetSlotController}）：
 * htmx 成功只换礼包 / 次数面板（{@code #user-entitlements-panel}，新面板带新的一次性 {@code grantToken}）+ toast；
 * 失败异常冒出由 {@code AdminBusinessExceptionAdvice} 落进表单 {@code hx-target}；非 htmx PRG + flash。
 * 门控 {@code user.entitlement_grant}（D-30，OPS_MANAGER 枚举预授）。
 */
@Controller
public class AdminEntitlementController {

    /** ⚠️ 表达式须与模板补发卡的 {@code sec:authorize} 逐字一致。 */
    static final String GRANT_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.USER_ENTITLEMENT_GRANT + "')";

    static final String PANEL_TARGET = "#user-entitlements-panel";

    private final AdminEntitlementGrantService grants;
    private final AdminUserEntitlementsService tabs;
    private final Messages msg;
    private final com.tailtopia.admin.usermgmt.service.AdminUserPurchasesQuery purchases;

    public AdminEntitlementController(AdminEntitlementGrantService grants, AdminUserEntitlementsService tabs,
            Messages msg, com.tailtopia.admin.usermgmt.service.AdminUserPurchasesQuery purchases) {
        this.grants = grants;
        this.tabs = tabs;
        this.msg = msg;
        this.purchases = purchases;
    }

    @PostMapping("/admin/users/{userId}/entitlements/grant")
    @PreAuthorize(GRANT_AUTH)
    public String grant(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam(value = "creditType", required = false) String creditType,
            @RequestParam(value = "quantity", required = false) String quantity,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "grantToken", required = false) String grantToken,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            AdminEntitlementGrantService.Result r = grants.grant(userId, parseType(creditType), parseQuantity(quantity),
                    reason, grantToken, admin.getAdminAccountId());
            populatePanel(userId, model);
            model.addAttribute("toast", toast(r));
            response.setHeader(AdminFragmentResponses.HEADER_RETARGET, PANEL_TARGET);
            response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "outerHTML");
            return "admin/fragments/drawer-users-entitlements :: afterGrant";
        }
        try {
            AdminEntitlementGrantService.Result r = grants.grant(userId, parseType(creditType), parseQuantity(quantity),
                    reason, grantToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", toast(r));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/users?open=" + userId;
    }

    /** 「已补发 {0} 次 {1}（有效期 3 个月）」：类型名按当前语言（KTP 模板拼「KTP 高清 · {卡种}」）。 */
    private String toast(AdminEntitlementGrantService.Result r) {
        return msg.get("admin.v150.valuePack.grant.toast", r.quantity(), typeName(r.type()));
    }

    String typeName(CreditType t) {
        String key = AdminUserEntitlementsService.typeKey(t);
        String template = AdminUserEntitlementsService.template(t);
        return template == null ? msg.get("admin.v150.valuePack.type." + key)
                : msg.get("admin.v150.valuePack.type.KTP_HD", msg.get("admin.v134.idcard.style." + template));
    }

    /** 页签片段的 model 属性（与 {@code AdminUserController.populateDrawer} 同名）：每次渲染换一个新的幂等 token。 */
    private void populatePanel(long userId, Model model) {
        model.addAttribute("entitlementsTab", tabs.tab(userId));
        model.addAttribute("entitlementGrantToken", UUID.randomUUID().toString());
        model.addAttribute("entitlementGrantTypes", AdminUserEntitlementsService.GRANTABLE);
        model.addAttribute("entitlementUserId", userId);
        model.addAttribute("purchases", purchases.forUser(userId)); // top 片段的空态判定要用
    }

    static CreditType parseType(String raw) {
        try {
            return CreditType.valueOf(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw AppException.validation("不能补发该类型").code("admin.err.entitlement.typeInvalid");
        }
    }

    static int parseQuantity(String raw) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw AppException.validation("补发次数须为 1–3").code("admin.err.entitlement.quantityInvalid");
        }
    }
}
