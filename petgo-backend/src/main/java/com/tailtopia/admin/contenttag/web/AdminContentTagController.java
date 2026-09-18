package com.tailtopia.admin.contenttag.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.contenttag.service.AdminContentTagService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import com.tailtopia.admin.tagicon.AdminTagIconService;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 内容装饰标签管理（Story 11.2 · AB-10C）。
 *
 * <h2>🛡 双权限码</h2>
 * 查看 {@code content.tag_view} / 编辑 {@code content.tag_manage}。
 * ⚠️ <b>侧栏 {@code sec:authorize} 表达式必须与这里的 {@code @PreAuthorize} 逐字一致</b> ——
 * 两边走散时权限放行了、敲 URL 能进，但侧栏没链接，运营只会以为自己没这个功能。
 *
 * <h2>🕗 时区</h2>
 * 生效时间按 <b>WIB</b> 解释、入库转 UTC；界面须在输入框旁明示「WIB」。
 * <b>结束时间可留空 = 永久分配</b>（本表比顶置排期多这一种情况）。
 */
@Controller
public class AdminContentTagController {

    static final String VIEW = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.CONTENT_TAG_VIEW + "')";
    static final String MANAGE = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.CONTENT_TAG_MANAGE + "')";

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final AdminContentTagService service;

    /**
     * 标签图标上传（Story 11.5）。
     *
     * <p>🛡 空文件表示"这次不改图标" —— 编辑标签时运营常常只改名称。
     */
    private final AdminTagIconService icons;

    private final Messages msg;

    public AdminContentTagController(AdminContentTagService service, AdminTagIconService icons,
            Messages msg) {
        this.service = service;
        this.icons = icons;
        this.msg = msg;
    }

    @GetMapping("/admin/content-tags")
    @PreAuthorize(VIEW)
    public String list(@RequestParam(value = "open", required = false) Long open,
            @RequestParam(value = "create", required = false) String create,
            HxRequest hx, Model model) {
        Instant now = Instant.now();
        model.addAttribute("active", "content-tags");
        // ⚠️ 取一次 now、查一次表，喂给摘要条与表格两处：各查各的话不只是多一倍查询，
        //    跨秒时两个数还能对不上（7.3 复审 ⑦ 同款）。
        var rows = service.listTags(now);
        model.addAttribute("tags", rows);
        model.addAttribute("summary", service.summary(rows));
        model.addAttribute("open", open);
        // ?create=1 深链（无 htmx 时直达新建表单 URL 的落点）。
        model.addAttribute("openCreate", create != null);
        // 2026-08-28：胶囊底色调色板（固定几档，见 ContentTagBadgeStyle 的说明）。
        model.addAttribute("badgeStyles", com.tailtopia.content.domain.ContentTagBadgeStyle.values());
        // htmx 局部刷新只回表格（摘要条随 oob 一并换）。
        return hx.isHtmx() ? "admin/fragments/tags-list :: rows(true)" : "admin/content-tags";
    }

    /**
     * 标签抽屉（Story 7.4 · AC2 / AC3）：页签一「编辑」、页签二「分配记录」。
     *
     * <p>页签切换也走这个端点（{@code tab=edit|assignments}）—— 两个页签用的是同一份标签数据，
     * 再拆一个端点只会多一处要保持同步的地方。非 htmx 直达 → 回列表并自动开该抽屉。
     */
    @GetMapping("/admin/content-tags/{id}/drawer")
    @PreAuthorize(VIEW)
    public String drawer(@PathVariable long id,
            @RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/content-tags?open=" + id;
        }
        populateDrawer(id, tab, page, model);
        return "admin/fragments/drawer-content-tag :: drawer";
    }

    /** 新建标签表单（AC4）：原先常驻页尾的那份，字段与 {@code POST} 参数逐字不变。 */
    @GetMapping("/admin/content-tags/new/drawer")
    @PreAuthorize(MANAGE)
    public String newDrawer(HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/content-tags?create=1";
        }
        model.addAttribute("active", "content-tags");
        populateForm(model);
        return "admin/fragments/drawer-content-tag :: createForm";
    }

    private void populateForm(Model model) {
        // AC3（11.2 既有）：尺寸规范文案常驻在上传控件旁（三语走 MessageSource）。
        model.addAttribute("iconSpec", icons.specText("contentTag"));
        model.addAttribute("badgeStyles", com.tailtopia.content.domain.ContentTagBadgeStyle.values());
    }

    private void populateDrawer(long id, String tab, int page, Model model) {
        Instant now = Instant.now();
        model.addAttribute("active", "content-tags");
        model.addAttribute("t", service.tag(id, now));
        model.addAttribute("tab", "assignments".equals(tab) ? "assignments" : "edit");
        var found = service.assignmentPage(id, now, page);
        model.addAttribute("assignments", found.rows());
        model.addAttribute("hasNext", found.hasNext());
        model.addAttribute("page", found.page());
        model.addAttribute("assignmentTotal", found.total());
        populateForm(model);
    }

    /** 抽屉内处置成功统一响应：抽屉重渲染 + toast + 列表刷新（摘要条与「生效中分配数」都要跟着变）。 */
    private String afterAction(long id, String tab, String toast, Model model, HttpServletResponse response) {
        populateDrawer(id, tab, 0, model);
        model.addAttribute("toast", toast);
        AdminFragmentResponses.trigger(response, AdminHxEvents.TAG_LIST_REFRESH);
        return "admin/fragments/drawer-content-tag :: afterAction";
    }

    /** 打标内容选择器（HTMX 局部）：只列可公开展示的内容，分页。 */
    @GetMapping("/admin/content-tags/pick")
    @PreAuthorize(VIEW)
    public String pick(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        model.addAttribute("candidates", service.pickable(q, page));
        return "admin/content-tags :: candidates";
    }

    /**
     * 新建标签（AC4）。htmx 分支成功后<b>不回新建表单</b>：发
     * {@code HX-Trigger: {"admin:drawer-open":{"url":"…/drawer?tab=assignments"}}}，
     * 让前端按正常流程打开新标签的抽屉并停在「分配记录」页签 —— 建完标签紧接着要做的就是给它加内容。
     */
    @PostMapping("/admin/content-tags")
    @PreAuthorize(MANAGE)
    public String createTag(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String name,
            @RequestParam(value = "iconFile", required = false) MultipartFile iconFile,
            @RequestParam String description,
            @RequestParam(value = "badgeStyle", required = false) String badgeStyle,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            long id = doCreate(admin, name, iconFile, description, badgeStyle);
            AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_OPEN,
                    "{\"url\":\"/admin/content-tags/" + id + "/drawer?tab=assignments\",\"id\":" + id + "}");
            AdminFragmentResponses.trigger(response, AdminHxEvents.TAG_LIST_REFRESH);
            model.addAttribute("toast", msg.get("admin.flash.contentTag.created"));
            return "admin/fragments/drawer-content-tag :: created";
        }
        try {
            doCreate(admin, name, iconFile, description, badgeStyle);
            flash.addFlashAttribute("notice", msg.get("admin.flash.contentTag.created"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/content-tags";
    }

    private long doCreate(AdminUserDetails admin, String name, MultipartFile iconFile,
            String description, String badgeStyle) {
        // Story 11.5：图标改为上传。🔴 新建时**必须**有图标 —— 没有图标的标签在
        // Feed 卡上只剩文字，与设计稿不符（规格里图标是胶囊的固定组成部分）。
        String iconUrl = icons.uploadOrKeep(iconFile);
        if (iconUrl == null) {
            throw AppException.validation(icons.iconRequiredMessage());
        }
        // 2026-09-02：标签码不再由运营填写，服务层自动生成（ct-<id>），杜绝撞码。
        return service.createTag(admin.getAdminAccountId(), name, iconUrl, description, badgeStyle);
    }

    @PostMapping("/admin/content-tags/{id}/edit")
    @PreAuthorize(MANAGE)
    public String editTag(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam String name,
            @RequestParam(value = "iconFile", required = false) MultipartFile iconFile,
            @RequestParam String description,
            @RequestParam(value = "badgeStyle", required = false) String badgeStyle,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // 🛡 没传新文件 → 传 null，服务层保留原图标（不是清空）。
            service.editTag(admin.getAdminAccountId(), id, name,
                    icons.uploadOrKeep(iconFile), description, badgeStyle);
            return afterAction(id, "edit", msg.get("admin.flash.contentTag.updated"), model, response);
        }
        try {
            service.editTag(admin.getAdminAccountId(), id, name,
                    icons.uploadOrKeep(iconFile), description, badgeStyle);
            flash.addFlashAttribute("notice", msg.get("admin.flash.contentTag.updated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/content-tags";
    }

    @PostMapping("/admin/content-tags/{id}/retire")
    @PreAuthorize(MANAGE)
    public String retire(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(defaultValue = "true") boolean retired,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        // ⚠️ 文案要说清"已分配的不受影响" —— 否则运营会以为下线等于全部收回。
        String toast = retired ? msg.get("admin.flash.contentTag.retired")
                : msg.get("admin.flash.contentTag.restored");
        if (hx.isHtmx()) {
            service.setRetired(admin.getAdminAccountId(), id, retired);
            return afterAction(id, "edit", toast, model, response);
        }
        try {
            service.setRetired(admin.getAdminAccountId(), id, retired);
            flash.addFlashAttribute("notice", toast);
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/content-tags";
    }

    @PostMapping("/admin/content-tags/assign")
    @PreAuthorize(MANAGE)
    public String assign(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(required = false) Long postId, @RequestParam long tagId,
            @RequestParam String startsAt,
            @RequestParam(required = false) String endsAt,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        // 结束时间留空 = 永久分配。
        if (hx.isHtmx()) {
            requirePost(postId);
            Instant to = (endsAt == null || endsAt.isBlank()) ? null : toInstant(endsAt);
            service.assign(admin.getAdminAccountId(), postId, tagId, toInstant(startsAt), to);
            // 打标后停在「分配记录」页签：刚加的那条就在第一行
            return afterAction(tagId, "assignments", msg.get("admin.flash.contentTag.assigned"),
                    model, response);
        }
        try {
            requirePost(postId);
            Instant to = (endsAt == null || endsAt.isBlank()) ? null : toInstant(endsAt);
            service.assign(admin.getAdminAccountId(), postId, tagId, toInstant(startsAt), to);
            flash.addFlashAttribute("notice", msg.get("admin.flash.contentTag.assigned"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/content-tags";
    }

    /**
     * 🔴 没选内容 → 自己抛 {@link AppException}，**不要让它变成缺参数的 400**。
     *
     * <p>参数从 {@code long} 改成 {@code Long}（端点与参数名不变）的唯一原因就是这个：
     * 候选项是一排 radio，在 480px 抽屉里离提交钮很远，很容易只填了时间就点「打标」。
     * 缺参数的 400 回的是 ProblemDetail JSON，而 {@code admin-core.js} 的 {@code htmx:beforeSwap}
     * 只放行 422/403/404 —— 响应被丢弃、err 槽空白、按钮弹回，界面上**什么都不发生**。
     */
    private static void requirePost(Long postId) {
        if (postId == null) {
            throw AppException.validation("请先选择要打标的内容")
                    .code("admin.err.contentTag.postRequired");
        }
    }

    @PostMapping("/admin/content-tags/assignments/{id}/remove")
    @PreAuthorize(MANAGE)
    public String unassign(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // 🔴 先读所属标签再删：删完就查不到了，而抽屉还要按这个标签重渲染。
            long tagId = service.tagIdOfAssignment(id)
                    .orElseThrow(() -> AppException.notFound(
                                    msg.get("admin.flash.contentTag.assignmentNotFound"))
                            // 文案码沿用整页分支那条 flash：不另造一条只有 htmx 用得上的键
                            .code("admin.flash.contentTag.assignmentNotFound"));
            service.unassign(admin.getAdminAccountId(), id);
            return afterAction(tagId, "assignments", msg.get("admin.flash.contentTag.unassigned"),
                    model, response);
        }
        boolean removed = service.unassign(admin.getAdminAccountId(), id);
        flash.addFlashAttribute(removed ? "notice" : "error",
                removed ? msg.get("admin.flash.contentTag.unassigned")
                        : msg.get("admin.flash.contentTag.assignmentNotFound"));
        return "redirect:/admin/content-tags";
    }

    /**
     * `datetime-local`（无时区）按 WIB 解释成绝对时刻。
     *
     * <p>⚠️ 不可用系统默认时区 —— 那会让"服务器在哪"决定标签的实际生效时刻。
     */
    private static Instant toInstant(String localDateTime) {
        try {
            return LocalDateTime.parse(localDateTime).atZone(WIB).toInstant();
        } catch (RuntimeException e) {
            throw AppException.validation("时间格式不正确").code("admin.err.contentTag.badDateTime");
        }
    }
}
