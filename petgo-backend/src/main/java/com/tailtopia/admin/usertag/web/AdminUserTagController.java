package com.tailtopia.admin.usertag.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.usertag.service.AdminUserTagService;
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

    @GetMapping("/admin/user-tags")
    @PreAuthorize(VIEW)
    public String list(@RequestParam(value = "tagId", required = false) Long tagId,
            @RequestParam(value = "userId", required = false) Long userId, Model model) {
        Instant now = Instant.now();
        model.addAttribute("active", "user-tags");
        model.addAttribute("tags", service.listTags(now));
        model.addAttribute("assignable", service.assignableTags());
        model.addAttribute("maxVisible", service.maxVisible());
        model.addAttribute("tagId", tagId);
        model.addAttribute("userId", userId);
        if (tagId != null) {
            model.addAttribute("assignments", service.assignmentsByTag(tagId, now));
        } else if (userId != null) {
            model.addAttribute("assignments", service.assignmentsByUser(userId, now));
            // 🔴 「已达展示上限」的提示依据：该用户当前生效中的分配数。
            model.addAttribute("activeCount", service.activeCount(userId, now));
        }
        // AC3：尺寸规范文案常驻在上传控件旁（三语走 MessageSource）。
        model.addAttribute("iconSpec", icons.specText("userTag"));
        // bug 20260828：用户选择器的首屏候选（htmx 之后再按关键词换）。
        model.addAttribute("candidates", service.pickableUsers(null, 0));
        // 2026-08-28：徽章底色调色板（固定几档，见 UserTagBadgeColor 的说明）。
        model.addAttribute("badgeColors", com.tailtopia.auth.domain.UserTagBadgeColor.values());
        return "admin/user-tags";
    }

    @PostMapping("/admin/user-tags")
    @PreAuthorize(MANAGE)
    public String createTag(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String code, @RequestParam String name,
            @RequestParam(value = "iconFile", required = false) MultipartFile iconFile,
            @RequestParam String description,
            @RequestParam(value = "badgeColor", required = false) String badgeColor,
            RedirectAttributes flash) {
        try {
            // Story 11.5：图标改为上传。🔴 新建时**必须**有图标 —— 没有图标的标签在
            // Feed 卡上只剩文字，与设计稿不符（规格里图标是胶囊的固定组成部分）。
            String iconUrl = icons.uploadOrKeep(iconFile);
            if (iconUrl == null) {
                throw AppException.validation(icons.iconRequiredMessage());
            }
            service.createTag(admin.getAdminAccountId(), code, name, iconUrl, description,
                    badgeColor);
            flash.addFlashAttribute("notice", msg.get("admin.flash.userTag.created"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/user-tags";
    }

    @PostMapping("/admin/user-tags/{id}/edit")
    @PreAuthorize(MANAGE)
    public String editTag(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam String name,
            @RequestParam(value = "iconFile", required = false) MultipartFile iconFile,
            @RequestParam String description,
            @RequestParam(value = "badgeColor", required = false) String badgeColor,
            RedirectAttributes flash) {
        try {
            // 🛡 没传新文件 → 传 null，服务层保留原图标（不是清空）。
            service.editTag(admin.getAdminAccountId(), id, name,
                    icons.uploadOrKeep(iconFile), description, badgeColor);
            flash.addFlashAttribute("notice", msg.get("admin.flash.userTag.updated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/user-tags";
    }

    @PostMapping("/admin/user-tags/{id}/retire")
    @PreAuthorize(MANAGE)
    public String retire(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(defaultValue = "true") boolean retired, RedirectAttributes flash) {
        try {
            service.setRetired(admin.getAdminAccountId(), id, retired);
            flash.addFlashAttribute("notice", retired
                    ? msg.get("admin.flash.userTag.retired")
                    : msg.get("admin.flash.userTag.restored"));
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
        return "admin/user-tags :: candidates";
    }

    @PostMapping("/admin/user-tags/assign")
    @PreAuthorize(MANAGE)
    public String assign(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(required = false) List<Long> pickedUserIds,
            @RequestParam(required = false) String userIds, @RequestParam long tagId,
            @RequestParam String startsAt,
            @RequestParam(required = false) String endsAt,
            RedirectAttributes flash) {
        try {
            // bug 20260828：两条入口合并 —— 选择器勾选的（pickedUserIds）+ 手填的（userIds）。
            // ⚠️ 手填那条**保留**：运营手上有时就是一串从别处导出的 id，
            //    逼他在候选表里一个个找反而更慢。两条走同一套服务端校验，
            //    所以保留它不会重新打开「分给注销用户」那个口子。
            List<Long> ids = new java.util.ArrayList<>();
            if (pickedUserIds != null) {
                ids.addAll(pickedUserIds);
            }
            ids.addAll(parseIds(userIds));
            if (ids.isEmpty()) {
                throw AppException.validation("请先勾选用户，或在下方手动填写用户 ID")
                        .code("admin.err.userTag.noneSelected");
            }
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
            RedirectAttributes flash) {
        boolean removed = service.unassign(admin.getAdminAccountId(), id);
        flash.addFlashAttribute(removed ? "notice" : "error",
                removed ? msg.get("admin.flash.userTag.unassigned")
                        : msg.get("admin.flash.userTag.assignmentNotFound"));
        return "redirect:/admin/user-tags";
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
