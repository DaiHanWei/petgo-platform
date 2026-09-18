package com.tailtopia.admin.virtual.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.virtual.service.AdminPublishIdentityService;
import jakarta.servlet.http.HttpServletResponse;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
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
import java.util.List;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台**运营发布身份**管理（Story 9.8 建虚拟账号侧；V1.1.6 Story 12.1 升格为两区）。
 * Thymeleaf admin slice，{@code /admin/virtual-accounts/**}，redirect+flash。
 *
 * <p><b>这一页现在管两类发布身份</b>：【虚拟账号】（本控制器）与【运营真实账号】
 * （{@link AdminPublishIdentityController}，独立权限码 {@code seed.publish_as_real}）。
 * 🛡 虚拟账号侧的既有能力（创建 / 列表 / 启停 / 导出）**一处未改**。
 *
 * <p>⚠️ <b>URL 刻意不改名</b>：改路径会让运营的收藏夹与既有测试一起失效，
 * 而"这一页管什么"是界面上的事，不是路径上的事。
 */
@Controller
public class AdminVirtualAccountController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')";

    /** 供 {@link AdminPublishIdentityController} 的禁用确认页复用（同一操作，同一门）。 */
    static final String MANAGE_AUTH = AUTH;

    /**
     * 列表页的门。
     *
     * <p>🔴 <b>必须把 {@code seed.publish_as_real} 也算进来</b>：只持有该码的人
     * （管真实发布身份但不管虚拟账号）否则会看得见侧栏入口、点进去 403。
     * 🛡 <b>本表达式与侧栏 {@code sec:authorize} 必须逐字一致。</b>
     */
    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.view')"
                    + " or hasAuthority('virtual_account.manage')"
                    + " or hasAuthority('seed.publish_as_real')";

    private final AdminVirtualAccountService service;
    private final AdminPublishIdentityService identities;
    private final com.tailtopia.admin.seed.service.AdminSeedImageService images;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminVirtualAccountController(AdminVirtualAccountService service,
            Messages msg,
            AdminPublishIdentityService identities,
            com.tailtopia.admin.seed.service.AdminSeedImageService images) {
        this.service = service;
        this.msg = msg;
        this.identities = identities;
        this.images = images;
    }

    /**
     * 「运营发布身份」页（V1.3.0 Story 8.3 起两块各套一次模板 B）。
     *
     * <p>⚠️ <b>两个关键词参数是两回事，刻意不合并</b>：
     * <ul>
     *   <li>{@code q} —— 区块二「搜索并纳入」的候选搜索（V1.1.6 既有，参数名不能动，
     *       只有真搜了才查，空搜索返回空表而不是把全站用户列出来）；</li>
     *   <li>{@code vq} —— 区块一虚拟账号列表的关键词筛选（8.3 新增）。</li>
     * </ul>
     * 用同一个 {@code q} 驱动两块的话，运营在上面找一个马甲会顺手把下面的候选表也刷成另一批人。
     *
     * <p>htmx 局部刷新按 {@code section} 分流：三块内容（虚拟账号表 / 候选表 / 池内表）
     * 都挂在同一个 URL 上，不给判据的话谁触发都只会拿到第一块。
     */
    @GetMapping("/admin/virtual-accounts")
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin,
            Model model, @RequestParam(required = false) String q,
            @RequestParam(required = false) String vq,
            @RequestParam(required = false) String species,
            @RequestParam(required = false) String section,
            @RequestParam(value = "open", required = false) Long open,
            @RequestParam(value = "create", required = false) String create,
            HxRequest hx) {
        model.addAttribute("active", "virtual-accounts");
        model.addAttribute("species", species);
        model.addAttribute("vq", vq);
        model.addAttribute("speciesOptions", com.tailtopia.content.species.ContentSpecies.ALL);
        var rows = service.list(species, vq);
        model.addAttribute("accounts", rows);
        model.addAttribute("summary", service.summary(rows));
        // 🔴🔴 区块二的数据**必须自己判权限**，不能靠模板上那句 sec:authorize 遮。
        //    整页上遮得住，但下面的 section 分流是**独立的 fragment 端点**，模板那层遮挡不存在：
        //    只持 virtual_account.view 的人请求 ?section=identities 就能拿到整个真实账号身份池
        //    （含**授权说明**、纳入人、经后台发布数）。这正是本页反复标红的
        //    「能管虚拟账号 ≠ 能以真人身份发言」被绕开的那条缝。
        boolean mayReal = AdminPublishIdentityService.mayPublishAsReal(admin);
        model.addAttribute("mayReal", mayReal);
        model.addAttribute("realAccounts", mayReal ? identities.listRealAccounts() : List.of());
        // 纳入候选：只有真搜了才查（空搜索返回空表，不会把全站用户列出来）。
        model.addAttribute("candidates", mayReal ? identities.searchCandidates(q) : List.of());
        model.addAttribute("q", q);
        model.addAttribute("open", open);
        model.addAttribute("openCreate", create != null);
        if (hx.isHtmx()) {
            String s = section == null ? "" : section;
            if (("candidates".equals(s) || "identities".equals(s)) && !mayReal) {
                // 与整页一致：没有这个码就当这一块不存在（403，不是回一张空表 —— 空表会被读成「池子是空的」）。
                throw new org.springframework.security.access.AccessDeniedException(
                        "seed.publish_as_real required");
            }
            return switch (s) {
                case "candidates" -> "admin/fragments/publish-identities :: candidates";
                case "identities" -> "admin/fragments/publish-identities :: rows(true)";
                default -> "admin/fragments/virtual-accounts-list :: rows(true)";
            };
        }
        return "admin/virtual-accounts";
    }

    /**
     * 虚拟账号抽屉（Story 8.3 · AC1）：资料 + 物种定位改写 + 启停。
     *
     * <p>非 htmx 直达 → 回列表并自动开该抽屉（与 B1～B8 同一机制）。
     */
    @GetMapping("/admin/virtual-accounts/{id}/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String drawer(@PathVariable long id, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/virtual-accounts?open=" + id;
        }
        populateDrawer(id, model);
        return "admin/fragments/drawer-virtual-account :: drawer";
    }

    /** 新建虚拟账号表单（Story 8.3 · AC1）：字段与 {@code POST} 参数逐字不变。 */
    @GetMapping("/admin/virtual-accounts/new/drawer")
    @PreAuthorize(AUTH)
    public String newDrawer(HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/virtual-accounts?create=1";
        }
        model.addAttribute("active", "virtual-accounts");
        model.addAttribute("speciesOptions", com.tailtopia.content.species.ContentSpecies.ALL);
        return "admin/fragments/drawer-virtual-account :: createForm";
    }

    private void populateDrawer(long id, Model model) {
        model.addAttribute("active", "virtual-accounts");
        model.addAttribute("a", service.one(id));
        model.addAttribute("speciesOptions", com.tailtopia.content.species.ContentSpecies.ALL);
    }

    /** 抽屉内处置成功统一响应：抽屉重渲染 + toast + 列表整表重拉（摘要条三格都要跟着变）。 */
    private String afterAction(long id, String toast, Model model, HttpServletResponse response) {
        populateDrawer(id, model);
        model.addAttribute("toast", toast);
        AdminFragmentResponses.trigger(response, AdminHxEvents.VIRTUAL_ACCOUNT_LIST_REFRESH);
        return "admin/fragments/drawer-virtual-account :: afterAction";
    }

    /**
     * 建虚拟账号。
     *
     * <p>🔴 V1.1.6 Story 12.2 · AC2 最后一条：头像**改成可以直接上传**
     * （原先同样只支持填 URL —— 运营得先去别处传图拿链接）。
     * 上传优先；两个都没给就没有头像（选填）。URL 输入框保留作兜底，
     * 理由与内容图一样：运营手上确实存在已有 CDN 链接的素材。
     */
    @PostMapping("/admin/virtual-accounts")
    @PreAuthorize(AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String nickname, @RequestParam(required = false) String avatarUrl,
            @RequestParam(required = false) MultipartFile avatarFile,
            @RequestParam(required = false) String accountSpecies,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // ⚠️ 上传失败（对象存储未配置 / 凭证异常）在这条路上**不能吞成成功**：
            //    交给 AdminBusinessExceptionAdvice 回 422 落进抽屉的行内错误槽。
            long id = doCreate(admin, nickname, avatarUrl, avatarFile, accountSpecies);
            AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_OPEN,
                    "{\"url\":\"/admin/virtual-accounts/" + id + "/drawer\",\"id\":" + id + "}");
            AdminFragmentResponses.trigger(response, AdminHxEvents.VIRTUAL_ACCOUNT_LIST_REFRESH);
            model.addAttribute("toast", msg.get("admin.flash.virtualAccount.created", id));
            return "admin/fragments/drawer-virtual-account :: created";
        }
        try {
            long id = doCreate(admin, nickname, avatarUrl, avatarFile, accountSpecies);
            flash.addFlashAttribute("notice", msg.get("admin.flash.virtualAccount.created", id));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        } catch (Exception e) {
            // 对象存储未配置 / 凭证异常 —— 优雅回显，不抛 500。
            flash.addFlashAttribute("error", msg.get("admin.flash.vet.avatarUploadFailed"));
        }
        return "redirect:/admin/virtual-accounts";
    }

    private long doCreate(AdminUserDetails admin, String nickname, String avatarUrl,
            MultipartFile avatarFile, String accountSpecies) {
        // 上传优先；两个都没给就没有头像（选填）。URL 输入框保留作兜底 ——
        // 运营手上确实存在已有 CDN 链接的素材。
        String finalUrl = avatarUrl;
        if (avatarFile != null && !avatarFile.isEmpty()) {
            try {
                finalUrl = images.upload(avatarFile, "virtual-avatar").url();
            } catch (AppException e) {
                throw e;
            } catch (Exception e) {
                throw AppException.validation("头像上传失败，请稍后重试")
                        .code("admin.flash.vet.avatarUploadFailed");
            }
        }
        return service.create(nickname, finalUrl, accountSpecies, admin.getAdminAccountId());
    }

    /**
     * 改账号物种定位（V1.1.6 Story 14.1 · AC2）。
     *
     * <p>✅ 改完立即影响该号**全部历史内容**的物种归属 —— 读时推导、零回填。
     * 所以这个下拉是本 story 里运营最常用的那一个。
     */
    @PostMapping("/admin/virtual-accounts/{id}/species")
    @PreAuthorize(AUTH)
    public String setSpecies(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, @RequestParam String accountSpecies,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            service.setAccountSpecies(id, accountSpecies, admin.getAdminAccountId());
            return afterAction(id, msg.get("admin.flash.virtualAccount.speciesUpdated"),
                    model, response);
        }
        try {
            service.setAccountSpecies(id, accountSpecies, admin.getAdminAccountId());
            flash.addFlashAttribute("notice",
                    msg.get("admin.flash.virtualAccount.speciesUpdated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/virtual-accounts";
    }

    @PostMapping("/admin/virtual-accounts/{id}/enabled")
    @PreAuthorize(AUTH)
    public String setEnabled(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam boolean enabled,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            service.setEnabled(id, enabled, admin.getAdminAccountId());
            // 🛡 停用走确认弹层（服务端算的待发布排期数），成功后要把弹层收掉。
            AdminFragmentResponses.trigger(response, AdminHxEvents.CONFIRM_CLOSE);
            return afterAction(id, msg.get("admin.flash.virtualAccount.statusUpdated"),
                    model, response);
        }
        try {
            service.setEnabled(id, enabled, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.virtualAccount.statusUpdated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/virtual-accounts";
    }
}
