package com.tailtopia.admin.usertag.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.usertag.service.AdminUserTagService;
import com.tailtopia.shared.error.AppException;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    public AdminUserTagController(AdminUserTagService service) {
        this.service = service;
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
        return "admin/user-tags";
    }

    @PostMapping("/admin/user-tags")
    @PreAuthorize(MANAGE)
    public String createTag(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String code, @RequestParam String name,
            @RequestParam String icon, @RequestParam String description,
            RedirectAttributes flash) {
        try {
            service.createTag(admin.getAdminAccountId(), code, name, icon, description);
            flash.addFlashAttribute("notice", "已新建用户标签");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/user-tags";
    }

    @PostMapping("/admin/user-tags/{id}/edit")
    @PreAuthorize(MANAGE)
    public String editTag(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam String name, @RequestParam String icon,
            @RequestParam String description, RedirectAttributes flash) {
        try {
            service.editTag(admin.getAdminAccountId(), id, name, icon, description);
            flash.addFlashAttribute("notice", "已更新标签");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
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
                    ? "已下线该标签：不能再分配，已分配的照旧生效到各自结束时间"
                    : "已重新上线该标签");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/user-tags";
    }

    /**
     * 批量分配：一个标签 → 多个用户。
     *
     * <p>用户 id 支持逗号 / 空白分隔，便于运营从名单直接粘贴。
     * 单个失败不拖垮整批，失败的 id 回显出来。
     */
    @PostMapping("/admin/user-tags/assign")
    @PreAuthorize(MANAGE)
    public String assign(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String userIds, @RequestParam long tagId,
            @RequestParam String startsAt,
            @RequestParam(required = false) String endsAt,
            RedirectAttributes flash) {
        try {
            List<Long> ids = parseIds(userIds);
            Instant to = (endsAt == null || endsAt.isBlank()) ? null : toInstant(endsAt);
            List<Long> failed = service.assignBulk(admin.getAdminAccountId(), ids, tagId,
                    toInstant(startsAt), to);
            if (failed.isEmpty()) {
                flash.addFlashAttribute("notice", "已为 " + ids.size() + " 个用户分配标签");
            } else {
                flash.addFlashAttribute("error",
                        "部分失败（" + failed.size() + " 个）：" + failed);
            }
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/user-tags";
    }

    @PostMapping("/admin/user-tags/assignments/{id}/remove")
    @PreAuthorize(MANAGE)
    public String unassign(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        boolean removed = service.unassign(admin.getAdminAccountId(), id);
        flash.addFlashAttribute(removed ? "notice" : "error",
                removed ? "已取消分配" : "该分配记录不存在");
        return "redirect:/admin/user-tags";
    }

    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation("请选择至少一个用户");
        }
        try {
            return Arrays.stream(raw.split("[,\\s]+"))
                    .filter(s -> !s.isBlank())
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException e) {
            throw AppException.validation("用户 ID 只能是数字，用逗号或空格分隔");
        }
    }

    /** `datetime-local`（无时区）按 WIB 解释。⚠️ 不可用系统默认时区。 */
    private static Instant toInstant(String localDateTime) {
        try {
            return LocalDateTime.parse(localDateTime).atZone(WIB).toInstant();
        } catch (RuntimeException e) {
            throw AppException.validation("时间格式不正确");
        }
    }
}
