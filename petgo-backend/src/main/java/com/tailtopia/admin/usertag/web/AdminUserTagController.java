package com.tailtopia.admin.usertag.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.usertag.service.AdminUserTagService;
import jakarta.servlet.http.HttpServletResponse;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
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
 * 用户标签管理（Story 11.3 · AB-12A）。
 *
 * <h2>🛡 双权限码</h2>
 * 查看 {@code user.tag_view} / 编辑 {@code user.tag_manage}。
 * ⚠️ 侧栏 {@code sec:authorize} 必须与这里的 {@code @PreAuthorize} 逐字一致。
 *
 * <p>⚠️ **编辑权限不要下放得比其它模块更宽** —— 本页的分配支持批量，
 * 是"一次影响很多用户"的动作。
 */
@Controller
public class AdminUserTagController {

    static final String VIEW = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.USER_TAG_VIEW + "')";
    static final String MANAGE = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.USER_TAG_MANAGE + "')";

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final AdminUserTagService service;

    /**
     * 标签图标上传（Story 11.5）。
     *
     * <p>🛡 空文件表示"这次不改图标" —— 编辑标签时运营常常只改名称。
     */
    private final AdminTagIconService icons;

    private final Messages msg;

    public AdminUserTagController(AdminUserTagService service, AdminTagIconService icons,
            Messages msg) {
        this.service = service;
        this.icons = icons;
        this.msg = msg;
    }

    /**
     * 主表（V1.3.0 Story 8.2 · AC1 起套模板 B）：**只有标签一张表**，
     * 编辑 / 分配记录 / 加人全部进抽屉。
     *
     * <p>⚠️ {@code tagId} / {@code userId} 是 1.1.6 那版「分配记录」筛选区块留下的参数，
     * 外部链接与运营的书签里还有。**不静默忽略**（7.4 复审留下的教训）：
     * <ul>
     *   <li>{@code ?tagId=} → 等价于 {@code ?open=}，直接把那个标签的抽屉开在「分配记录」页签；</li>
     *   <li>{@code ?userId=} → 「按用户看」这个维度随筛选区块一并退役，给一条说明而不是一张空表。</li>
     * </ul>
     */
    @GetMapping("/admin/user-tags")
    @PreAuthorize(VIEW)
    public String list(@RequestParam(value = "tagId", required = false) Long tagId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "open", required = false) Long open,
            @RequestParam(value = "create", required = false) String create,
            HxRequest hx, Model model) {
        Instant now = Instant.now();
        model.addAttribute("active", "user-tags");
        // ⚠️ 取一次 now、查一次表，喂给摘要条与表格两处：各查各的话跨秒时两个数能对不上。
        var rows = service.listTags(now);
        model.addAttribute("tags", rows);
        model.addAttribute("summary", service.summary(rows));
        model.addAttribute("maxVisible", service.maxVisible());
        model.addAttribute("open", open);
        // 旧书签 ?tagId=<id> 的原意就是「看这个标签的分配记录」。
        // ⚠️ 不能只把它当成 ?open= 塞给深链兜底：admin-drawer.js 的兜底分支**只在 URL 带 ?open= 时才跑**，
        //    而旧书签的 URL 上没有 ?open=，那样这条路会一声不响地什么都不做。
        //    走 [data-drawer-open][data-drawer-autoopen] 那条既有通道（?create=1 用的同一条），
        //    它在加载时直接按给定 URL 开抽屉，还能带上 tab=assignments。
        model.addAttribute("legacyTagId", open == null ? tagId : null);
        model.addAttribute("legacyUserId", userId);
        // ?create=1 深链（无 htmx 时直达新建表单 URL 的落点）。
        model.addAttribute("openCreate", create != null);
        // htmx 局部刷新只回表格（摘要条随 oob 一并换）。
        return hx.isHtmx() ? "admin/fragments/user-tags-list :: rows(true)" : "admin/user-tags";
    }

    /**
     * 标签抽屉（Story 8.2 · AC2 / AC3）：页签一「编辑」、页签二「分配记录」。
     *
     * <p>页签切换也走这个端点（{@code tab=edit|assignments}）—— 与 7.4 内容标签同构。
     * 非 htmx 直达 → 回列表并自动开该抽屉。
     */
    @GetMapping("/admin/user-tags/{id}/drawer")
    @PreAuthorize(VIEW)
    public String drawer(@PathVariable long id,
            @RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/user-tags?open=" + id;
        }
        populateDrawer(id, tab, page, model);
        return "admin/fragments/drawer-user-tag :: drawer";
    }

    /** 新建标签表单（AC4）：原先常驻页尾的那份，字段与 {@code POST} 参数逐字不变。 */
    @GetMapping("/admin/user-tags/new/drawer")
    @PreAuthorize(MANAGE)
    public String newDrawer(HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/user-tags?create=1";
        }
        model.addAttribute("active", "user-tags");
        populateForm(model);
        return "admin/fragments/drawer-user-tag :: createForm";
    }

    private void populateForm(Model model) {
        // AC3（11.3 既有）：尺寸规范文案常驻在上传控件旁（三语走 MessageSource）。
        model.addAttribute("iconSpec", icons.specText("userTag"));
    }

    private void populateDrawer(long id, String tab, int page, Model model) {
        Instant now = Instant.now();
        model.addAttribute("active", "user-tags");
        model.addAttribute("t", service.tag(id, now));
        model.addAttribute("tab", "assignments".equals(tab) ? "assignments" : "edit");
        model.addAttribute("maxVisible", service.maxVisible());
        var found = service.assignmentPage(id, now, page);
        model.addAttribute("assignments", found.rows());
        model.addAttribute("hasNext", found.hasNext());
        model.addAttribute("page", found.page());
        model.addAttribute("assignmentTotal", found.total());
        populateForm(model);
    }

    /** 抽屉内处置成功统一响应：抽屉重渲染 + toast + 列表刷新（摘要条与「生效中分配数」都要跟着变）。 */
    private String afterAction(long id, String tab, String toast, Model model,
            HttpServletResponse response) {
        populateDrawer(id, tab, 0, model);
        model.addAttribute("toast", toast);
        AdminFragmentResponses.trigger(response, AdminHxEvents.USER_TAG_LIST_REFRESH);
        return "admin/fragments/drawer-user-tag :: afterAction";
    }

    /**
     * 新建标签（AC4）。htmx 分支成功后<b>不回新建表单</b>：发
     * {@code HX-Trigger: {"admin:drawer-open":{"url":"…/drawer?tab=assignments"}}}，
     * 让前端按正常流程打开新标签的抽屉并停在「分配记录」页签 ——
     * 建完标签紧接着要做的就是给它加人。
     */
    @PostMapping("/admin/user-tags")
    @PreAuthorize(MANAGE)
    public String createTag(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String name,
            @RequestParam(value = "iconFile", required = false) MultipartFile iconFile,
            @RequestParam String description,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            long id = doCreate(admin, name, iconFile, description);
            AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_OPEN,
                    "{\"url\":\"/admin/user-tags/" + id + "/drawer?tab=assignments\",\"id\":" + id + "}");
            AdminFragmentResponses.trigger(response, AdminHxEvents.USER_TAG_LIST_REFRESH);
            model.addAttribute("toast", msg.get("admin.flash.userTag.created"));
            return "admin/fragments/drawer-user-tag :: created";
        }
        try {
            doCreate(admin, name, iconFile, description);
            flash.addFlashAttribute("notice", msg.get("admin.flash.userTag.created"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/user-tags";
    }

    private long doCreate(AdminUserDetails admin, String name, MultipartFile iconFile,
            String description) {
        // Story 11.5：图标改为上传。🔴 新建时**必须**有图标 —— 2026-09-02 起
        // 上传的图**就是用户看到的整枚标签**（14×14 整图显示，无衬底）。
        String iconUrl = icons.uploadOrKeep(iconFile);
        if (iconUrl == null) {
            throw AppException.validation(icons.iconRequiredMessage());
        }
        // 2026-09-02：标签码不再由运营填写，服务层自动生成（ut-<id>），杜绝撞码。
        return service.createTag(admin.getAdminAccountId(), name, iconUrl, description);
    }

    @PostMapping("/admin/user-tags/{id}/edit")
    @PreAuthorize(MANAGE)
    public String editTag(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam String name,
            @RequestParam(value = "iconFile", required = false) MultipartFile iconFile,
            @RequestParam String description,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // 🛡 没传新文件 → 传 null，服务层保留原图标（不是清空）。
            service.editTag(admin.getAdminAccountId(), id, name,
                    icons.uploadOrKeep(iconFile), description);
            return afterAction(id, "edit", msg.get("admin.flash.userTag.updated"), model, response);
        }
        try {
            // 🛡 没传新文件 → 传 null，服务层保留原图标（不是清空）。
            service.editTag(admin.getAdminAccountId(), id, name,
                    icons.uploadOrKeep(iconFile), description);
            flash.addFlashAttribute("notice", msg.get("admin.flash.userTag.updated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/user-tags";
    }

    @PostMapping("/admin/user-tags/{id}/retire")
    @PreAuthorize(MANAGE)
    public String retire(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(defaultValue = "true") boolean retired,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        // ⚠️ 文案要说清"已分配的不受影响" —— 否则运营会以为下线等于全部收回。
        String toast = retired ? msg.get("admin.flash.userTag.retired")
                : msg.get("admin.flash.userTag.restored");
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
        return "redirect:/admin/user-tags";
    }

    /**
     * 批量分配：一个标签 → 多个用户。
     *
     * <p>用户 id 支持逗号 / 空白分隔，便于运营从名单直接粘贴。
     * 单个失败不拖垮整批，失败的 id 回显出来。
     */
    /**
     * 用户选择器的候选片段（bug 20260828，htmx 局部刷新）。
     *
     * <p>与内容标签的 {@code /admin/content-tags/pick} 同形状 —— 运营在两页之间切换时
     * 不该遇到两套不同的挑选方式。
     *
     * <p>🔴 已注销账号在查询层就不出现（{@code UserRepository#searchTaggableUsers}）。
     */
    @GetMapping("/admin/user-tags/pick")
    @PreAuthorize(MANAGE)
    public String pick(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        model.addAttribute("candidates", service.pickableUsers(q, page));
        // ⚠️ 候选片段里的「满 N 会顶掉最早的」预告要用它 —— 这个端点是 htmx 单独拉的，
        //    模型不会从整页那次渲染继承任何东西，漏了它比较表达式就是拿 null 比大小。
        model.addAttribute("maxVisible", service.maxVisible());
        return "admin/user-tags :: candidates";
    }

    @PostMapping("/admin/user-tags/assign")
    @PreAuthorize(MANAGE)
    public String assign(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(required = false) List<Long> pickedUserIds,
            @RequestParam(required = false) String userIds, @RequestParam long tagId,
            @RequestParam String startsAt,
            @RequestParam(required = false) String endsAt,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            List<Long> ids = mergeIds(pickedUserIds, userIds);
            Instant to = (endsAt == null || endsAt.isBlank()) ? null : toInstant(endsAt);
            List<Long> failed = service.assignBulk(admin.getAdminAccountId(), ids, tagId,
                    toInstant(startsAt), to);
            // 🔴 部分失败是**部分成功**，两件事都要说：
            //    ① 抽屉照常重渲染、列表照常刷新 —— 成功的那几条必须当场看得见；
            //    ② 失败的 id 落在分配表单自己的行内错误槽里（本响应唯一的非 oob 内容）。
            //    ⚠️ 曾经写成「有失败就抛 422」：那样已经入库的几条既不显示、列表也不刷新，
            //       运营的合理解读是「整批都没成功」，于是原样再提交一遍。
            if (!failed.isEmpty()) {
                model.addAttribute("partialError",
                        msg.get("admin.flash.userTag.assignPartialFailed", failed.size(), failed));
            }
            // 分配后停在「分配记录」页签：刚加的那几条就在最前面。
            return afterAction(tagId, "assignments",
                    msg.get("admin.flash.userTag.assignedBulk", ids.size() - failed.size()),
                    model, response);
        }
        try {
            List<Long> ids = mergeIds(pickedUserIds, userIds);
            Instant to = (endsAt == null || endsAt.isBlank()) ? null : toInstant(endsAt);
            List<Long> failed = service.assignBulk(admin.getAdminAccountId(), ids, tagId,
                    toInstant(startsAt), to);
            if (failed.isEmpty()) {
                flash.addFlashAttribute("notice",
                        msg.get("admin.flash.userTag.assignedBulk", ids.size()));
            } else {
                flash.addFlashAttribute("error",
                        msg.get("admin.flash.userTag.assignPartialFailed", failed.size(), failed));
            }
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/user-tags";
    }

    @PostMapping("/admin/user-tags/assignments/{id}/remove")
    @PreAuthorize(MANAGE)
    public String unassign(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        var removedFromTag = service.unassign(admin.getAdminAccountId(), id);
        if (hx.isHtmx()) {
            // 🔴 「没找到」当 404 抛，不当成功：两种结果都回同一份抽屉的话，那条记录会原地不动，
            //    运营会以为「点了没反应」再点一次。
            long tagId = removedFromTag.orElseThrow(() -> AppException.notFound("分配记录不存在")
                    .code("admin.flash.userTag.assignmentNotFound"));
            return afterAction(tagId, "assignments", msg.get("admin.flash.userTag.unassigned"),
                    model, response);
        }
        flash.addFlashAttribute(removedFromTag.isPresent() ? "notice" : "error",
                removedFromTag.isPresent() ? msg.get("admin.flash.userTag.unassigned")
                        : msg.get("admin.flash.userTag.assignmentNotFound"));
        return "redirect:/admin/user-tags";
    }

    /**
     * 两条入口合并（bug 20260828）：选择器勾选的（{@code pickedUserIds}）+ 手填的（{@code userIds}）。
     *
     * <p>⚠️ 手填那条**保留**：运营手上有时就是一串从别处导出的 id，逼他在候选表里一个个找反而更慢。
     * 两条走同一套服务端校验，所以留着它不会重新打开「分给注销用户」那个口子。
     */
    private static List<Long> mergeIds(List<Long> pickedUserIds, String userIds) {
        // 🔴 **在这里就去重**（保持顺序）：服务层虽然也去重，但控制器拿 ids.size() 报数
        //    ——「候选表里勾了 1001，手填框又粘了 1001, 1002」正是保留手填框的典型用法，
        //    不去重的话 toast 会说「已为 3 个用户分配」而实际入库 2 条，
        //    数字与分配记录页签里的行数对不上，运营会以为漏了一条、再提交一遍。
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        if (pickedUserIds != null) {
            ids.addAll(pickedUserIds);
        }
        ids.addAll(parseIds(userIds));
        if (ids.isEmpty()) {
            throw AppException.validation("请先勾选用户，或在下方手动填写用户 ID")
                    .code("admin.err.userTag.noneSelected");
        }
        return List.copyOf(ids);
    }

    /**
     * 手填框的 id 解析。**留空是合法的**（bug 20260828 起）——
     * 用户可以只在选择器里勾选、一个字都不填。「一个都没选」由 {@code assign} 统一判，
     * 它才同时看得到两条入口；在这里抛会让「只用选择器」这条正路直接报错。
     */
    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(raw.split("[,\\s]+"))
                    .filter(s -> !s.isBlank())
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException e) {
            throw AppException.validation("用户 ID 只能是数字，用逗号或空格分隔")
                    .code("admin.err.userTag.idsNotNumeric");
        }
    }

    /** `datetime-local`（无时区）按 WIB 解释。⚠️ 不可用系统默认时区。 */
    private static Instant toInstant(String localDateTime) {
        try {
            return LocalDateTime.parse(localDateTime).atZone(WIB).toInstant();
        } catch (RuntimeException e) {
            throw AppException.validation("时间格式不正确").code("admin.err.userTag.badDateTime");
        }
    }
}
