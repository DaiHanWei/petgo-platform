package com.tailtopia.admin.failedrequest.web;

import com.tailtopia.admin.failedrequest.service.FailedConsultRequestService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
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
 * 问诊请求未成功队列（Story 2.9，AB-2G）。活动/归档分区；SYSTEM_FAILURE 视觉预警 + 强制跟进方可归档。
 * 门控 {@code @PreAuthorize(vet.view)}（接诊能力监控归兽医管理权限组）；跟进/归档/备注写审计（service 层）。
 */
@Controller
public class FailedRequestAdminController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('vet.view')";

    private final FailedConsultRequestService service;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public FailedRequestAdminController(FailedConsultRequestService service,
            Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    @GetMapping("/admin/failed-requests")
    @PreAuthorize(AUTH)
    public String list(@RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "open", required = false) Long open,
            HxRequest hx, Model model) {
        model.addAttribute("active", "failed-requests");
        // 页签用查询参数而不是前端切 hidden：两个区是两次查询，且运营会把「已归档」那一页存书签。
        boolean archived = "archived".equals(tab);
        model.addAttribute("tab", archived ? "archived" : "active");
        var rows = archived ? service.archived() : service.active();
        model.addAttribute("items", rows);
        // ⚠️ 摘要与表格同源、随页签联动（在「已归档」页签上给活动区的数，读起来是骗人的）。
        model.addAttribute("summary", service.summary(rows));
        model.addAttribute("open", open);
        return hx.isHtmx() ? "admin/fragments/failed-requests-list :: rows(true)" : "admin/failed-requests";
    }

    /**
     * 抽屉（Story 9.2 · AC1）：完整字段 + 三个**独立**动作。
     *
     * <p>非 htmx 直达 → 回列表并自动开该抽屉（与 B1～B20 同一机制）。
     */
    @GetMapping("/admin/failed-requests/{id}/drawer")
    @PreAuthorize(AUTH)
    public String drawer(@PathVariable long id, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/failed-requests?open=" + id;
        }
        populateDrawer(id, model);
        return "admin/fragments/drawer-failed-request :: drawer";
    }

    private void populateDrawer(long id, Model model) {
        model.addAttribute("active", "failed-requests");
        model.addAttribute("r", service.one(id));
    }

    /**
     * 处置成功统一响应：抽屉重渲染 + 列表整表重拉（跟进 / 归档都会改摘要条，归档还会让行换区）。
     *
     * <p>⚠️ **不做单行 oob**：归档会把这一行从「活动」移走 —— 原位换行会留下一张骗人的表
     * （行还在活动页签里，只是状态变了）。
     *
     * <p>⚠️ 无 JS 的整页 PRG 分支一律回默认（活动）页签，不带回 {@code tab}：本 story 的硬约束是
     * 三个写端点的**路径 / 参数 / 权限逐字不动**，为回跳带一个 {@code tab} 参数就破了这条。
     * htmx 分支（常态）由页内 {@code #failed-refresh} 按当前页签整表重拉，不受影响。
     */
    private String afterAction(long id, String toast, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        populateDrawer(id, model);
        model.addAttribute("toast", toast);
        AdminFragmentResponses.trigger(response, AdminHxEvents.FAILED_REQUEST_LIST_REFRESH);
        return "admin/fragments/drawer-failed-request :: afterAction";
    }

    @PostMapping("/admin/failed-requests/{id}/follow-up")
    @PreAuthorize(AUTH)
    public String followUp(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, jakarta.servlet.http.HttpServletResponse response,
            RedirectAttributes flash) {
        service.followUp(id, admin.getAdminAccountId());
        if (hx.isHtmx()) {
            return afterAction(id, msg.get("admin.flash.failed.followedUp"), model, response);
        }
        flash.addFlashAttribute("notice", msg.get("admin.flash.failed.followedUp"));
        return "redirect:/admin/failed-requests";
    }

    @PostMapping("/admin/failed-requests/{id}/archive")
    @PreAuthorize(AUTH)
    public String archive(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, jakarta.servlet.http.HttpServletResponse response,
            RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // 🔴 SYSTEM_FAILURE 未跟进时实体会抛（422）——**不在这里吞**：
            //    让它经 AdminBusinessExceptionAdvice 落进抽屉的行内错误槽，
            //    运营看到的是「请先标记跟进」，而不是一次什么都没发生的点击。
            service.archive(id, admin.getAdminAccountId());
            return afterAction(id, msg.get("admin.flash.failed.archived"), model, response);
        }
        try {
            service.archive(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.failed.archived"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/failed-requests";
    }

    @PostMapping("/admin/failed-requests/{id}/note")
    @PreAuthorize(AUTH)
    public String note(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("note") String note,
            HxRequest hx, Model model, jakarta.servlet.http.HttpServletResponse response,
            RedirectAttributes flash) {
        service.note(id, note, admin.getAdminAccountId());
        if (hx.isHtmx()) {
            return afterAction(id, msg.get("admin.flash.failed.noteSaved"), model, response);
        }
        flash.addFlashAttribute("notice", msg.get("admin.flash.failed.noteSaved"));
        return "redirect:/admin/failed-requests";
    }
}
