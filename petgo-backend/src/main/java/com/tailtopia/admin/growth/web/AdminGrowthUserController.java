package com.tailtopia.admin.growth.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.growth.service.AdminGrowthUserService;
import com.tailtopia.admin.growth.service.AdminGrowthUserService.Preview;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 用户抽屉「成长值」页签的局部端点（V1.4.0 ① Story 4.2 · AB-33 · UI I1 ~ I7）。页签首屏随抽屉一次渲染
 * （{@code AdminUserController.populateDrawer} 装 {@code growthTab} / {@code growthGrantToken}），这里只管：
 * 明细筛选 / 翻页（只回明细子片段）、调整预检（回服务端确认弹层到 {@code #confirm-host}）、确认入账（只换成长值页签）。
 *
 * <p>🛡 明细门 = 抽屉的 {@code user.view}；两个调整端点门 = {@code growth.user_adjust}（直接 POST 无码 403）。
 * 虚拟号 / 注销 / 停用由服务端 422，不靠模板隐藏。
 */
@Controller
public class AdminGrowthUserController {

    /** 与 {@code AdminUserController.AUTH} 逐字一致（抽屉的查看门）。 */
    static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('user.view')";
    static final String GROWTH_ADJUST_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.GROWTH_USER_ADJUST + "')";

    private final AdminGrowthUserService service;
    private final Messages msg;

    public AdminGrowthUserController(AdminGrowthUserService service, Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    /** 明细筛选 / 翻页（AC2.6）：只回 {@code #user-growth-ledger}。非 htmx → 回列表并自动开该抽屉。 */
    @GetMapping("/admin/users/{userId}/growth/ledger")
    @PreAuthorize(VIEW_AUTH)
    public String ledger(@PathVariable long userId, @RequestParam(value = "rule", required = false) String rule,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", required = false) String page, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/users?open=" + userId;
        }
        model.addAttribute("gl", service.ledger(userId, rule, from, to, page));
        model.addAttribute("ruleOptions", AdminGrowthUserService.RULE_OPTIONS);
        return "admin/fragments/drawer-users-growth :: ledger";
    }

    /** 调整预检（AC3.3 / AC4）：只校验 + 按库里当前值算前后，不写库；失败 422 落 {@code #user-drawer-err}。 */
    @PostMapping("/admin/users/{userId}/growth/adjust/confirm")
    @PreAuthorize(GROWTH_ADJUST_AUTH)
    public String confirm(@PathVariable long userId, @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "delta", required = false) String delta,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "grantToken", required = false) String grantToken, HxRequest hx, Model model,
            HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/users?open=" + userId;
        }
        Preview preview = service.previewAdjust(userId, type, delta, note, grantToken);
        model.addAttribute("cm", confirmModal(preview));
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#confirm-host");
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
        return "admin/fragments/drawer-users-growth :: confirm";
    }

    /**
     * 确认入账（AC5 / AC6）：成功 = 收弹层 + 只换成长值页签（{@code #user-growth-panel} outerHTML，新 token）+ toast；
     * 422 / 403 回弹层内 {@code #confirm-modal-err}。同一 token 重复确认不重复入账、toast 照常。
     */
    @PostMapping("/admin/users/{userId}/growth/adjust")
    @PreAuthorize(GROWTH_ADJUST_AUTH)
    public String adjust(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "delta", required = false) String delta,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "grantToken", required = false) String grantToken, HxRequest hx, Model model,
            HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            service.adjust(userId, type, delta, note, grantToken, admin.getAdminAccountId());
            // 在 swap 之后再收弹层：先收的话发请求的表单已不在 DOM 里
            response.setHeader("HX-Trigger-After-Swap", AdminHxEvents.CONFIRM_CLOSE);
            response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#user-growth-panel");
            response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "outerHTML");
            model.addAttribute("growthTab", service.tab(userId));
            model.addAttribute("growthGrantToken", UUID.randomUUID().toString());
            model.addAttribute("toast", msg.get("admin.flash.growth.adjusted", signedDelta(delta)));
            return "admin/fragments/drawer-users-growth :: saved";
        }
        try {
            service.adjust(userId, type, delta, note, grantToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.growth.adjusted", signedDelta(delta)));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/users?open=" + userId;
    }

    /** toast 的「{+50}」：按提交值（已过服务端校验；重复提交时累计没变，但 toast 照常复述这次点的那个数）。 */
    private static String signedDelta(String deltaRaw) {
        return AdminGrowthUserService.signed(Long.parseLong(deltaRaw.trim()));
    }

    private AdminConfirmModal confirmModal(Preview p) {
        String typeLabel = msg.get("admin.v140.growth.adjust.type." + p.type().name()) + " · " + p.note();
        List<AdminConfirmModal.Fact> facts = List.of(
                new AdminConfirmModal.Fact(msg.get("admin.v140.growth.adjust.confirm.user"),
                        (p.displayName() == null ? "" : p.displayName() + " ") + "#" + p.userId()),
                new AdminConfirmModal.Fact(msg.get("admin.v140.growth.adjust.confirm.type"), typeLabel),
                new AdminConfirmModal.Fact(msg.get("admin.v140.growth.adjust.confirm.delta"),
                        msg.get("admin.v140.growth.exp", AdminGrowthUserService.signed(p.delta()))));
        List<AdminConfirmModal.DiffLine> diffs = List.of(
                new AdminConfirmModal.DiffLine(msg.get("admin.v140.growth.adjust.confirm.total"),
                        String.format(Locale.ROOT, "%,d", p.oldTotal()), String.format(Locale.ROOT, "%,d", p.newTotal())),
                new AdminConfirmModal.DiffLine(msg.get("admin.v140.growth.adjust.confirm.level"),
                        "Lv" + p.oldLevel(), "Lv" + p.newLevel()));
        Map<String, String> hidden = new LinkedHashMap<>();
        hidden.put("type", p.type().name());
        hidden.put("delta", String.valueOf(p.delta()));
        hidden.put("note", p.note());
        hidden.put("grantToken", p.grantToken());
        return new AdminConfirmModal(msg.get("admin.v140.growth.adjust.confirm.title"), null, diffs,
                msg.get("admin.v140.growth.adjust.confirm.note"), null, null, null,
                "/admin/users/" + p.userId() + "/growth/adjust", hidden,
                msg.get("admin.v140.growth.adjust.confirm.btn"), false, false, facts);
    }
}
