package com.tailtopia.admin.risk.web;

import com.tailtopia.admin.risk.dto.RedOverageRow;
import com.tailtopia.admin.risk.service.RedOverageMonitorService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import java.util.List;
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
 * 后台红色超额只读监控（Story 9.6，AB-7A → V1.3.0 Story 8.5 套模板 B）。
 * Thymeleaf admin slice，{@code /admin/red-overage}。门控：看 {@code risk.view} / 标记 {@code risk.edit}。
 * <b>纯观测 + 人工标记，无自动拦截</b>。
 */
@Controller
public class AdminRedOverageController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('risk.view')";
    private static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('risk.edit')";

    private final RedOverageMonitorService service;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminRedOverageController(RedOverageMonitorService service,
            Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    @GetMapping("/admin/red-overage")
    @PreAuthorize(AUTH)
    public String list(@RequestParam(value = "open", required = false) Long open,
            HxRequest hx, Model model) {
        model.addAttribute("active", "red-overage");
        // ⚠️ 取一次列表喂给摘要条与表格两处：各查各的话跨秒时两个数能对不上。
        List<RedOverageRow> rows = service.list();
        model.addAttribute("rows", rows);
        model.addAttribute("summary", service.summary(rows));
        model.addAttribute("open", open);
        return hx.isHtmx() ? "admin/fragments/red-overage-list :: rows(true)" : "admin/red-overage";
    }

    /**
     * 用户抽屉（Story 8.5 · AC4）：RED 分诊历史 + 标记操作。
     *
     * <p>非 htmx 直达 → 回列表并自动开该抽屉（与 B1～B13 同一机制）。
     */
    @GetMapping("/admin/red-overage/{userId}/drawer")
    @PreAuthorize(AUTH)
    public String drawer(@PathVariable long userId, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/red-overage?open=" + userId;
        }
        populateDrawer(userId, model);
        return "admin/fragments/drawer-red-overage :: drawer";
    }

    /**
     * 抽屉数据。
     *
     * <p>🔴 行取自 {@code list()} 的聚合结果而不是另写一个查询：RED 计数与复核态两个数的口径
     * 必须与列表那一行**逐字一致**，否则抽屉里写 7 次、列表里写 8 次，运营只会当成数据错乱。
     * 用户不在聚合结果里（一次 RED 都没有）→ 404，而不是渲染一个 0 次的空抽屉。
     */
    private void populateDrawer(long userId, Model model) {
        model.addAttribute("active", "red-overage");
        RedOverageRow row = service.list().stream().filter(r -> r.userId() == userId).findFirst()
                .orElseThrow(() -> AppException.notFound("无该用户的 RED 记录")
                        .code("admin.err.redOverage.notFound"));
        model.addAttribute("r", row);
        model.addAttribute("history", service.history(userId));
    }

    /** 该用户必须在 RED 聚合结果里；否则 404（与抽屉同一条判据）。 */
    private void requireInList(long userId) {
        if (service.list().stream().noneMatch(r -> r.userId() == userId)) {
            throw AppException.notFound("无该用户的 RED 记录").code("admin.err.redOverage.notFound");
        }
    }

    /** 标记成功统一响应：抽屉重渲染 + 列表那一行 oob + 摘要条 oob + toast。 */
    private String afterAction(long userId, String toast, Model model) {
        List<RedOverageRow> rows = service.list();
        model.addAttribute("active", "red-overage");
        model.addAttribute("summary", service.summary(rows));
        RedOverageRow row = rows.stream().filter(r -> r.userId() == userId).findFirst().orElseThrow();
        model.addAttribute("r", row);
        model.addAttribute("row", row);
        model.addAttribute("history", service.history(userId));
        model.addAttribute("toast", toast);
        return "admin/fragments/drawer-red-overage :: afterAction";
    }

    @PostMapping("/admin/red-overage/{userId}/review")
    @PreAuthorize(EDIT_AUTH)
    public String review(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam String status, @RequestParam(required = false) String note,
            HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // 🔴 先确认这个用户真的在聚合结果里（= 有过 RED 分诊）。
            //    service.mark 对任意 userId 都会写一条 review 行，而列表只列有 RED 记录的人：
            //    对一个没有 RED 记录的 id 直接 POST（改地址栏 / 脚本），会**写库成功**、
            //    随后 afterAction 找不到行抛 NoSuchElementException → 500，
            //    留下一条永远不会出现在任何页面上的孤儿 review 行。抽屉只可能从列表里打开，
            //    所以这里与 drawer 用同一条判据：不在列表里 = 404。
            requireInList(userId);
            // 非法状态值在服务层抛 422，这里不重复判 —— 让它落进抽屉的行内错误槽。
            service.mark(userId, status, note, admin.getAdminAccountId());
            return afterAction(userId, msg.get("admin.flash.redOverage.marked"), model);
        }
        try {
            service.mark(userId, status, note, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.redOverage.marked"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/red-overage";
    }
}
