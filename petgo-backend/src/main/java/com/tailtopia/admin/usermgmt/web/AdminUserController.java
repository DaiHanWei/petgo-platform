package com.tailtopia.admin.usermgmt.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.usermgmt.dto.AdminUserRow;
import com.tailtopia.admin.usermgmt.service.AdminUserService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    /**
     * 每页条数。V1.3.0 Story 8.1 起 20（AC1，与其余模板 B 列表一致）。
     *
     * <p>⚠️ 搜索态仍**不分页**：服务层按昵称模糊封顶 50 条，那是「找一个人」的用法，
     * 翻页对它没有意义（口径见 {@code AdminUserService.search}）。
     */
    private static final int PAGE_SIZE = 20;

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
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "open", required = false) Long open,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "users");
        model.addAttribute("q", q);
        model.addAttribute("open", open);
        // 手机号筛选（Story 11.4）：filled / empty / 不筛。
        model.addAttribute("phone", phone);
        // 账号状态筛选（Story 8.1 · AC1）：active / deactivated / deleted / 不筛。
        model.addAttribute("status", status);
        boolean searched = q != null && !q.isBlank();
        model.addAttribute("searched", searched);
        if (searched) {
            // 搜索：ID / 注册邮箱精确 + 昵称与邮箱模糊（服务层封顶 50 条），不分页；
            // 手机号与状态在内存里再筛一道 —— 三个筛选在同一个 form 里，少筛一个就和摘要条对不上。
            model.addAttribute("results", adminUserService.search(q, phone, status));
            model.addAttribute("page", null);
        } else {
            // bug 20260701-164：默认分页列出全部普通用户（id 倒序）；
            // 手机号与状态两个筛选都在 SQL 的 WHERE 里，分页才算得对。
            Page<AdminUserRow> pageResult = adminUserService.listFiltered(phone, status,
                    PageRequest.of(Math.max(page, 0), PAGE_SIZE));
            model.addAttribute("results", pageResult.getContent());
            model.addAttribute("page", pageResult);
        }
        // 摘要条与列表同一次筛选（Story 8.1 · AC1）：一条聚合出四个数。
        model.addAttribute("summary", adminUserService.summary(q, phone, status));
        // htmx 局部刷新只回表格（摘要条随 oob 一并换）。
        return hxRequest != null ? "admin/fragments/users-list :: rows(true)" : "admin/users";
    }

    /**
     * 用户详情抽屉（V1.3.0 Story 8.1 · AC2）：五页签一次性渲染，切页签不再请求。
     *
     * <p>📌 <b>整页 {@code GET /admin/users/{userId}} 已删除</b>（AC4）：处置一个用户不该跳走再回来。
     * 不做旧地址跳转（D-23）。非 htmx 直达本地址 → 回列表并自动开该抽屉。
     */
    @GetMapping("/admin/users/{userId}/drawer")
    @PreAuthorize(AUTH)
    public String userDrawer(@PathVariable long userId,
            org.springframework.security.core.Authentication auth,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/users?open=" + userId;
        }
        populateDrawer(userId, auth, model);
        return "admin/fragments/drawer-users :: drawer";
    }

    private void populateDrawer(long userId, org.springframework.security.core.Authentication auth,
            Model model) {
        model.addAttribute("active", "users");
        // 🛡 无 user.phone_view 权限 → 服务端**根本不装**手机号（不是模板隐藏）。
        //    只在模板里隐藏等于数据已经到了浏览器，看源码或抓接口就能拿到。
        boolean canSeePhone = hasPhoneView(auth);
        model.addAttribute("canSeePhone", canSeePhone);
        model.addAttribute("user", adminUserService.detail(userId, canSeePhone));
        // 赠币表单一次性幂等 token（bug 20260728-389）：防双击/回退重提交重复入账。
        model.addAttribute("grantToken", java.util.UUID.randomUUID().toString());
    }

    /**
     * 抽屉内处置成功的统一响应：抽屉重渲染 + 该行 oob + toast。
     *
     * <p>⚠️ 只换**这一行**、不整表重拉：本页的筛选与排序都不受处置影响（停用不会让行换位置），
     * 整表重拉只会让运营刚翻到的那一页跳回去。
     */
    private String afterAction(long userId, org.springframework.security.core.Authentication auth,
            String toast, Model model, jakarta.servlet.http.HttpServletResponse response) {
        populateDrawer(userId, auth, model);
        model.addAttribute("row", adminUserService.row(userId));
        model.addAttribute("toast", toast);
        com.tailtopia.admin.shared.web.AdminFragmentResponses.trigger(response,
                com.tailtopia.admin.shared.web.AdminHxEvents.BADGE_REFRESH);
        return "admin/fragments/drawer-users :: afterAction";
    }

    /** 后台赠送 PawCoin（bug 20260728-389，user.grant_pawcoin 门控）。 */
    @PostMapping("/admin/users/{userId}/grant-pawcoin")
    @PreAuthorize(GRANT_PAWCOIN_AUTH)
    public String grantPawCoin(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long userId, @RequestParam("coins") long coins,
            @RequestParam("reason") String reason,
            @RequestParam("idempotencyToken") String idempotencyToken,
            org.springframework.security.core.Authentication auth,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model,
            jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            adminUserService.grantPawCoin(userId, coins, reason, idempotencyToken,
                    admin.getAdminAccountId());
            return afterAction(userId, auth, msg.get("admin.flash.user.pawcoinGranted", coins),
                    model, response);
        }
        try {
            adminUserService.grantPawCoin(userId, coins, reason, idempotencyToken,
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.user.pawcoinGranted", coins));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/users?open=" + userId;
    }

    @PostMapping("/admin/users/{userId}/deactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String deactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam("reason") String reason,
            org.springframework.security.core.Authentication auth,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model,
            jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            adminUserService.deactivate(userId, reason, admin.getAdminAccountId());
            return afterAction(userId, auth, msg.get("admin.flash.user.deactivated"), model, response);
        }
        try {
            adminUserService.deactivate(userId, reason, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.user.deactivated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/users?open=" + userId;
    }

    @PostMapping("/admin/users/{userId}/reactivate")
    @PreAuthorize(DEACTIVATE_AUTH)
    public String reactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            org.springframework.security.core.Authentication auth,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model,
            jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        adminUserService.reactivate(userId, admin.getAdminAccountId());
        if (hx.isHtmx()) {
            return afterAction(userId, auth, msg.get("admin.flash.user.reactivated"), model, response);
        }
        flash.addFlashAttribute("notice", msg.get("admin.flash.user.reactivated"));
        return "redirect:/admin/users?open=" + userId;
    }

    @PostMapping("/admin/users/{userId}/delete")
    @PreAuthorize(DELETE_AUTH)
    public String delete(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam("type") String type, @RequestParam("note") String note,
            org.springframework.security.core.Authentication auth,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model,
            jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            adminUserService.deleteUser(userId,
                    com.tailtopia.admin.usermgmt.domain.DeletionType.fromOrNull(type), note,
                    admin.getAdminAccountId());
            // ⚠️ 删除后这一行**仍在列表里**（状态变「已注销」，历史可查），所以照旧换行 + 重渲染抽屉。
            // 🔴 deletionPending：级联注销是 7.3 的 @Async，`deletedAt` 此刻还没落库 ——
            //    不显式传这个标记的话，重渲染出来的抽屉里三张处置卡还在，
            //    运营能在「注销已提交」的提示下面再点一次删除（再写一条审计、再触发一次级联）。
            String result = afterAction(userId, auth, msg.get("admin.flash.user.deletionSubmitted"),
                    model, response);
            model.addAttribute("deletionPending", true);
            return result;
        }
        try {
            adminUserService.deleteUser(userId,
                    com.tailtopia.admin.usermgmt.domain.DeletionType.fromOrNull(type), note,
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.user.deletionSubmitted"));
            return "redirect:/admin/users";
        } catch (com.tailtopia.shared.error.AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
            return "redirect:/admin/users?open=" + userId;
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
