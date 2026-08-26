package com.tailtopia.admin.virtual.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminPublishIdentityService;
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

    @GetMapping("/admin/virtual-accounts")
    @PreAuthorize(VIEW_AUTH)
    public String list(Model model, @RequestParam(required = false) String q,
            @RequestParam(required = false) String species) {
        model.addAttribute("active", "virtual-accounts");
        model.addAttribute("species", species);
        model.addAttribute("speciesOptions", com.tailtopia.content.species.ContentSpecies.ALL);
        model.addAttribute("accounts", service.list(species));
        model.addAttribute("realAccounts", identities.listRealAccounts());
        // 纳入候选：只有真搜了才查（空搜索返回空表，不会把全站用户列出来）。
        model.addAttribute("candidates", identities.searchCandidates(q));
        model.addAttribute("q", q);
        return "admin/virtual-accounts";
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
            RedirectAttributes flash) {
        try {
            String finalUrl = avatarUrl;
            if (avatarFile != null && !avatarFile.isEmpty()) {
                finalUrl = images.upload(avatarFile, "virtual-avatar").url();
            }
            long id = service.create(nickname, finalUrl, accountSpecies,
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.virtualAccount.created", id));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        } catch (Exception e) {
            // 对象存储未配置 / 凭证异常 —— 优雅回显，不抛 500。
            flash.addFlashAttribute("error", "头像上传失败，请重试");
        }
        return "redirect:/admin/virtual-accounts";
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
            @PathVariable long id, @RequestParam String accountSpecies, RedirectAttributes flash) {
        try {
            service.setAccountSpecies(id, accountSpecies, admin.getAdminAccountId());
            flash.addFlashAttribute("notice",
                    "已更新账号物种定位（该号全部历史内容的物种归属已随之生效）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/virtual-accounts";
    }

    @PostMapping("/admin/virtual-accounts/{id}/enabled")
    @PreAuthorize(AUTH)
    public String setEnabled(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam boolean enabled, RedirectAttributes flash) {
        try {
            service.setEnabled(id, enabled, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.virtualAccount.statusUpdated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/virtual-accounts";
    }
}
