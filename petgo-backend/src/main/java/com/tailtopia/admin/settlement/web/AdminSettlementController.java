package com.tailtopia.admin.settlement.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.settlement.service.AdminSettlementService;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
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
 * 后台兽医分成月结对账（Story 9.5，AB-8D）。Thymeleaf admin slice，{@code /admin/settlements/**}，redirect+flash。
 * 门控：对账查看 {@code settlement.view} / 确认打款·归档 {@code settlement.payout}（SUPER_ADMIN 隐式全权）。
 */
@Controller
public class AdminSettlementController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('settlement.view')";
    private static final String PAYOUT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('settlement.payout')";

    private final AdminSettlementService service;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminSettlementController(AdminSettlementService service,
            Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    @GetMapping("/admin/settlements")
    @PreAuthorize(VIEW_AUTH)
    public String list(@RequestParam(value = "open", required = false) Long open,
            HxRequest hx, Model model) {
        model.addAttribute("active", "settlements");
        // ⚠️ 取一次列表喂给摘要条与表格两处：各查各的话跨秒时两个数能对不上。
        var rows = service.list();
        model.addAttribute("settlements", rows);
        model.addAttribute("summary", service.summary(rows));
        model.addAttribute("open", open);
        return hx.isHtmx() ? "admin/fragments/settlements-list :: rows(true)" : "admin/settlements";
    }

    /**
     * 月结抽屉（Story 8.5 · AC3）：订单构成 + 凭证 + 操作条。
     *
     * <p>非 htmx 直达 → 回列表并自动开该抽屉（与 B1～B11 同一机制）。
     */
    @GetMapping("/admin/settlements/{id}/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String drawer(@PathVariable long id, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/settlements?open=" + id;
        }
        populateDrawer(id, model);
        return "admin/fragments/drawer-settlement :: drawer";
    }

    private void populateDrawer(long id, Model model) {
        model.addAttribute("active", "settlements");
        model.addAttribute("s", service.detail(id));
    }

    /**
     * 抽屉内处置成功统一响应：抽屉重渲染 + 列表那一行 oob + 摘要条 oob + toast。
     *
     * <p>⚠️ 不整表重拉：打款 / 归档不会让行换位置（表按 generatedAt 倒序），整表重拉只会把
     * 运营刚翻到的位置跳回去。🔴 但摘要条三格都会变（待打款少一单、本月已打款多一笔），
     * 所以行之外**还要**换摘要条 —— 只换行的话那三个数会留在旧值。
     */
    private String afterAction(long id, String toast, Model model) {
        populateDrawer(id, model);
        var rows = service.list();
        model.addAttribute("summary", service.summary(rows));
        model.addAttribute("row", rows.stream().filter(r -> r.id() == id).findFirst().orElseThrow());
        model.addAttribute("toast", toast);
        return "admin/fragments/drawer-settlement :: afterAction";
    }

    @PostMapping("/admin/settlements/{id}/pay")
    @PreAuthorize(PAYOUT_AUTH)
    public String pay(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(required = false) String proof,
            HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // 状态机守卫在 VetSettlement（非法跃迁抛 422），这里不重复判 —— 让它落进行内错误槽。
            service.markPaid(id, proof, admin.getAdminAccountId());
            return afterAction(id, msg.get("admin.flash.settlement.paid"), model);
        }
        try {
            service.markPaid(id, proof, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.settlement.paid"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/settlements";
    }

    @PostMapping("/admin/settlements/{id}/archive")
    @PreAuthorize(PAYOUT_AUTH)
    public String archive(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            service.archive(id, admin.getAdminAccountId());
            return afterAction(id, msg.get("admin.flash.settlement.archived"), model);
        }
        try {
            service.archive(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.settlement.archived"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/settlements";
    }
}
