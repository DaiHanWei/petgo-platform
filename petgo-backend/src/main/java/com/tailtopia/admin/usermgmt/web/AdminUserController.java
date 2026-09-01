package com.tailtopia.admin.usermgmt.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.usermgmt.dto.AdminUserRow;
import com.tailtopia.admin.usermgmt.service.AdminUserService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台用户搜索与详情（Story 3.1，AB-UA-01）。**纯只读 GET**，SSR + HTMX，不返 JSON、不写审计。
 * 门控 {@code @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('user.view')")}。
 */
@Controller
public class AdminUserController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('user.view')";
    /**
     * 召回名单导出（Story 11.4）。🛡 与"查看手机号"是**两个**权限码 ——
     * 导出把 PII 批量带出系统，风险高一档。
     */
    private static final String EXPORT_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.USER_PHONE_EXPORT + "')";
    private static final String DEACTIVATE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('user.deactivate')";
    private static final String DELETE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('user.delete')";
    private static final String GRANT_PAWCOIN_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('user.grant_pawcoin')";
    private static final int PAGE_SIZE = 50;

    private final AdminUserService adminUserService;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminUserController(AdminUserService adminUserService,
            Messages msg) {
        this.adminUserService = adminUserService;
        this.msg = msg;
    }

    @GetMapping("/admin/users")
    @PreAuthorize(AUTH)
    public String users(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "users");
        model.addAttribute("q", q);
        // 手机号筛选（Story 11.4）：filled / empty / 不筛。
        model.addAttribute("phone", phone);
        boolean searched = q != null && !q.isBlank();
        model.addAttribute("searched", searched);
        Boolean phoneFilled = "filled".equals(phone) ? Boolean.TRUE
                : ("empty".equals(phone) ? Boolean.FALSE : null);
        if (!searched && phoneFilled != null) {
            Page<AdminUserRow> pageResult = adminUserService.listByPhoneFilled(phoneFilled,
                    PageRequest.of(Math.max(page, 0), PAGE_SIZE));
            model.addAttribute("results", pageResult.getContent());
            model.addAttribute("page", pageResult);
        } else if (searched) {
            // 精确搜索：按 ID / 注册邮箱命中 0 或 1 条，不分页。
            model.addAttribute("results", adminUserService.search(q));
            model.addAttribute("page", null);
        } else {
            // bug 20260701-164：默认分页列出全部普通用户（id 倒序），顶部搜索框保留。
            Page<AdminUserRow> pageResult = adminUserService.list(
                    PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id")));
            model.addAttribute("results", pageResult.getContent());
            model.addAttribute("page", pageResult);
        }
        return hxRequest != null ? "admin/users :: rows" : "admin/users";
    }

    @GetMapping("/admin/users/{userId}")
    @PreAuthorize(AUTH)
    public String userDetail(@PathVariable long userId,
            org.springframework.security.core.Authentication auth, Model model) {
        model.addAttribute("active", "users");
        // 🛡 无 user.phone_view 权限 → 服务端**根本不装**手机号（不是模板隐藏）。
        //    只在模板里隐藏等于数据已经到了浏览器，看源码或抓接口就能拿到。
        boolean canSeePhone = hasPhoneView(auth);
        model.addAttribute("canSeePhone", canSeePhone);
        model.addAttribute("user", adminUserService.detail(userId, canSeePhone));
        // 赠币表单一次性幂等 token（bug 20260728-389）：防双击/回退重提交重复入账。
        model.addAttribute("grantToken", java.util.UUID.randomUUID().toString());
        return "admin/user-detail";
    }

    /** 后台赠送 PawCoin（bug 20260728-389，user.grant_pawcoin 门控）。 */
    @PostMapping("/admin/users/{userId}/grant-pawcoin")
    @PreAuthorize(GRANT_PAWCOIN_AUTH)
    public String grantPawCoin(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long userId, @RequestParam("coins") long coins,
            @RequestParam("reason") String reason,
            @RequestParam("idempotencyToken") String idempotencyToken, RedirectAttributes flash) {
        try {
            adminUserService.grantPawCoin(userId, coins, reason, idempotencyToken,
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.user.pawcoinGranted", coins));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/users/" + userId;
    }

    @PostMapping("/admin/users/{userId}/deactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String deactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam("reason") String reason, RedirectAttributes flash) {
        try {
            adminUserService.deactivate(userId, reason, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.user.deactivated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/users/" + userId;
    }

    @PostMapping("/admin/users/{userId}/reactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String reactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            RedirectAttributes flash) {
        adminUserService.reactivate(userId, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", msg.get("admin.flash.user.reactivated"));
        return "redirect:/admin/users/" + userId;
    }

    @PostMapping("/admin/users/{userId}/delete")
    @PreAuthorize(DELETE_AUTH)
    public String delete(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam("type") String type, @RequestParam("note") String note,
            RedirectAttributes flash) {
        try {
            adminUserService.deleteUser(userId,
                    com.tailtopia.admin.usermgmt.domain.DeletionType.fromOrNull(type), note,
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.user.deletionSubmitted"));
            return "redirect:/admin/users";
        } catch (com.tailtopia.shared.error.AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
            return "redirect:/admin/users/" + userId;
        }
    }

    /**
     * 召回名单导出（Story 11.4 · AB-11A）。
     *
     * <p>🔴 独立权限 {@code user.phone_export} —— 与"查看"分开：
     * 查看是一次看一个人，导出是把 PII **批量带出系统**，风险高一档。
     *
     * <p>🛡 名单**不自动剔除已封号账号，但每行标注账号状态**，由运营自行判断。
     * 导出动作记审计（操作人 / 时间 / 条数 / 筛选条件），号码本身绝不进审计摘要。
     */
    // bug 20260901-469：产物改真 .xlsx（原 CSV 在运营 Excel 里挤成一列）。
    // 🔴 phone 严格必填且只认 filled/empty —— 原来的 defaultValue="empty" 是事故根源：
    //    页面链接一旦丢参（翻页丢参就是这么发生的），导出的不是报错而是**恰好相反的那份名单**，
    //    而两份名单长得一模一样，运营看不出来拿错了。
    @GetMapping(value = "/admin/users/phone-recall.xlsx")
    @PreAuthorize(EXPORT_AUTH)
    public ResponseEntity<byte[]> exportRecallList(
            @AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "phone") String phone) {
        if (!"filled".equals(phone) && !"empty".equals(phone)) {
            throw com.tailtopia.shared.error.AppException
                    .validation("导出前请先选择手机号筛选（已填写 / 未填写）")
                    .code("admin.err.users.exportNeedsFilter");
        }
        boolean filled = "filled".equals(phone);
        byte[] body = adminUserService.exportRecallList(admin.getAdminAccountId(), filled);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"phone-recall.xlsx\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    /** 是否持有手机号查看权限。⚠️ 表达式须与侧栏/模板的 sec:authorize 逐字一致。 */
    private static boolean hasPhoneView(org.springframework.security.core.Authentication auth) {
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_SUPER_ADMIN".equals(a.getAuthority())
                        || AdminPermissions.USER_PHONE_VIEW.equals(a.getAuthority()));
    }
}
