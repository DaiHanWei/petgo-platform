package com.tailtopia.admin.virtual.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.virtual.service.AdminPublishIdentityService;
import jakarta.servlet.http.HttpServletResponse;
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
 * 运营发布身份池 —— 真实账号侧的写操作与两个确认页（V1.1.6 Story 12.1 · AB-3I）。
 *
 * <p>列表页本身仍是 {@code /admin/virtual-accounts}（那一页已升格为「运营发布身份」，分两区），
 * 由 {@link AdminVirtualAccountController} 渲染。本控制器只负责：纳入 / 移出 / 两个确认页。
 *
 * <p>🔴 门控用**独立**权限码 {@code seed.publish_as_real}，与 {@code virtual_account.manage}
 * 完全解耦：<b>能管虚拟账号 ≠ 能以真人身份发言</b>。本版本仅分配给超级管理员（OQ-24）。
 */
@Controller
public class AdminPublishIdentityController {

    /** 🛡 与侧栏 {@code sec:authorize} 必须**逐字一致**，否则会出现"看得见入口点进去 403"。 */
    static final String REAL_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('seed.publish_as_real')";

    /**
     * 按该账号过滤的排期列表路径（AC4 的"跳转链接"）。
     *
     * <p>📌 <b>这个页面由 Story 13.5（定时发布与排期管理）交付</b>，本 story 落地时还不存在。
     * 链接**只在待发布排期数 &gt; 0 时才渲染**，而在 13.4/13.5 开始产生排期之前那个数为 0，
     * 所以今天线上不会出现死链。改了路径改这一个常量即可。
     *
     * <p>⚠️ V1.3.0 Story 7.5：独立排期页退役，排期并入批量内容页第二页签 —— 常量随之改成
     * 带页签参数的地址（后面还要拼 {@code &authorId=}，所以这里用的是 {@code ?} 开头的查询串，
     * 下面拼接时**必须是 {@code &}**）。按发布账号筛选在新页签里原样保留，这条链接照常能用。
     */
    static final String SCHEDULE_LIST_PATH = "/admin/seed-batches?tab=schedules";

    private final AdminPublishIdentityService identities;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminPublishIdentityController(AdminPublishIdentityService identities, Messages msg) {
        this.identities = identities;
        this.msg = msg;
    }

    /** 纳入候选搜索 + 纳入表单都在列表页上，这里只处理提交。 */
    @PostMapping("/admin/publish-identities")
    @PreAuthorize(REAL_AUTH)
    public String grant(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long userId, @RequestParam String authorizationNote,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            identities.grant(userId, authorizationNote, admin.getAdminAccountId());
            populateAfterPoolChange(model, userId);
            model.addAttribute("toast", msg.get("admin.flash.publishIdentity.granted", userId));
            return "admin/fragments/publish-identities :: granted";
        }
        try {
            identities.grant(userId, authorizationNote, admin.getAdminAccountId());
            flash.addFlashAttribute("notice",
                    msg.get("admin.flash.publishIdentity.granted", userId));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/virtual-accounts";
    }

    /**
     * 移出前的确认页（AC4）。
     *
     * <p>🔴 <b>为什么必须是服务端渲染的一页，而不是浏览器的 confirm()</b>：
     * 这一页要显示「该账号当前有 N 条待发布排期」—— N 只有服务端算得出来。
     * 而这个提示是本条 AC 的全部意义：不提示的话，那 N 条会在未来若干天里
     * <b>陆续、分散地失败</b>，运营要等到失败才发现，而届时内容的时间窗多半已经错过。
     * 移出是个瞬间的、有明确操作人的动作，是成本最低的提示时机。
     */
    @GetMapping("/admin/publish-identities/{userId}/remove/confirm")
    @PreAuthorize(REAL_AUTH)
    public String removeConfirm(@PathVariable long userId, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            // 整页确认路由已退役（AC3）：非 htmx 直达就回列表，不做旧地址跳转以外的兜底（D-23）。
            return "redirect:/admin/virtual-accounts";
        }
        model.addAttribute("active", "virtual-accounts");
        return confirmModel(model, userId, "REMOVE_REAL",
                "/admin/publish-identities/" + userId + "/remove");
    }

    @PostMapping("/admin/publish-identities/{userId}/remove")
    @PreAuthorize(REAL_AUTH)
    public String remove(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long userId,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // 🛡 「被种子内容引用的身份不可移出」由服务层判定（引用数 > 0 拒绝）——
            //    这里不重复判，让它抛 422 落进弹层里的行内错误槽。
            identities.remove(userId, admin.getAdminAccountId());
            AdminFragmentResponses.trigger(response, AdminHxEvents.CONFIRM_CLOSE);
            populateAfterPoolChange(model, userId);
            // 🛡 措辞刻意写明"未封号"：运营最常误解的就是这一点。
            model.addAttribute("toast", msg.get("admin.flash.publishIdentity.removed", userId));
            return "admin/fragments/publish-identities :: granted";
        }
        try {
            identities.remove(userId, admin.getAdminAccountId());
            // 🛡 措辞刻意写明"未封号"：运营最常误解的就是这一点。
            flash.addFlashAttribute("notice",
                    msg.get("admin.flash.publishIdentity.removed", userId));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/virtual-accounts";
    }

    /**
     * 虚拟账号**禁用**前的确认页（AC4 最后一条）。
     *
     * <p>⚠️ V1.1.0 原文没有这条 —— 那时还没有定时发布能力。引入排期后，
     * 「禁用一个虚拟账号」与「移出一个真实账号」对排期的后果<b>完全一样</b>，
     * 所以走同一个确认页、同一个计数口径。🛡 两处各判一次的话，口径迟早分叉。
     *
     * <p>🛡 <b>只有禁用要确认，启用不要</b>：启用是无害的。
     */
    @GetMapping("/admin/virtual-accounts/{userId}/disable/confirm")
    @PreAuthorize(AdminVirtualAccountController.MANAGE_AUTH)
    public String disableConfirm(@PathVariable long userId, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            // 整页确认路由已退役（AC3）。
            return "redirect:/admin/virtual-accounts?open=" + userId;
        }
        model.addAttribute("active", "virtual-accounts");
        return confirmModel(model, userId, "DISABLE_VIRTUAL",
                "/admin/virtual-accounts/" + userId + "/enabled");
    }

    /**
     * 纳入 / 移出成功后要换的两块：池表（整表 oob）+ **候选表里那一行**（定点 oob）。
     *
     * <p>🔴 候选行必须一起换：不换的话，刚纳入的那个人在上面的候选表里**仍然是可点的「纳入」按钮**，
     * 运营再点一次拿到 422（AC2 要求「已在池内的候选显示『已在池内』禁用」）。
     * 移出时反过来：那一行要重新变回可纳入。
     *
     * <p>⚠️ 不整表重拉候选：重拉得带上当时的搜索词 {@code q}，而 POST 请求里没有它。
     * 这里按 **id 精确**查一次；那个人不在当前候选结果里（运营搜的是别人）就查不到，
     * 不渲染 oob 行即可 —— 页面上本来也没有那一行。
     */
    private void populateAfterPoolChange(Model model, long userId) {
        model.addAttribute("realAccounts", identities.listRealAccounts());
        model.addAttribute("candidateRow", identities.searchCandidates(String.valueOf(userId))
                .stream().findFirst().orElse(null));
    }

    private String confirmModel(Model model, long userId, String mode, String action) {
        long pending = identities.pendingScheduleCount(userId);
        model.addAttribute("targetUserId", userId);
        model.addAttribute("mode", mode);
        model.addAttribute("actionPath", action);
        model.addAttribute("pendingSchedules", pending);
        // 🛡 只在真有排期时给链接：那一页由 13.5 交付，13.5 之前 pending 恒 0 ⇒ 不会渲染死链。
        model.addAttribute("scheduleListUrl",
                pending > 0 ? SCHEDULE_LIST_PATH + "&authorId=" + userId : null);
        // V1.3.0 Story 8.3：整页确认退役，同一份内容改由弹层片段承载（口径一个字没动）。
        return "admin/fragments/publish-identity-confirm :: modal";
    }
}
