package com.tailtopia.admin.contenttag.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.contenttag.service.AdminContentTagService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
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

    public AdminContentTagController(AdminContentTagService service, AdminTagIconService icons) {
        this.service = service;
        this.icons = icons;
    }

    @GetMapping("/admin/content-tags")
    @PreAuthorize(VIEW)
    public String list(@RequestParam(value = "tagId", required = false) Long tagId,
            @RequestParam(value = "postId", required = false) Long postId, Model model) {
        Instant now = Instant.now();
        model.addAttribute("active", "content-tags");
        model.addAttribute("tags", service.listTags(now));
        model.addAttribute("assignable", service.assignableTags());
        model.addAttribute("tagId", tagId);
        model.addAttribute("postId", postId);
        // 两种维度看分配情况（AC5）：按标签看"这个标签发给了谁"，按内容看"这条内容挂了什么"。
        if (tagId != null) {
            model.addAttribute("assignments", service.assignmentsByTag(tagId, now));
        } else if (postId != null) {
            model.addAttribute("assignments", service.assignmentsByPost(postId));
        }
        // AC3：尺寸规范文案常驻在上传控件旁（三语走 MessageSource）。
        model.addAttribute("iconSpec", icons.specText("contentTag"));
        return "admin/content-tags";
    }

    /** 打标内容选择器（HTMX 局部）：只列可公开展示的内容，分页。 */
    @GetMapping("/admin/content-tags/pick")
    @PreAuthorize(VIEW)
    public String pick(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        model.addAttribute("candidates", service.pickable(q, page));
        return "admin/content-tags :: candidates";
    }

    @PostMapping("/admin/content-tags")
    @PreAuthorize(MANAGE)
    public String createTag(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String code, @RequestParam String name,
            @RequestParam(value = "iconFile", required = false) MultipartFile iconFile,
            @RequestParam String description,
            RedirectAttributes flash) {
        try {
            // Story 11.5：图标改为上传。🔴 新建时**必须**有图标 —— 没有图标的标签在
            // Feed 卡上只剩文字，与设计稿不符（规格里图标是胶囊的固定组成部分）。
            String iconUrl = icons.uploadOrKeep(iconFile);
            if (iconUrl == null) {
                throw AppException.validation(icons.iconRequiredMessage());
            }
            service.createTag(admin.getAdminAccountId(), code, name, iconUrl, description);
            flash.addFlashAttribute("notice", "已新建装饰标签");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/content-tags";
    }

    @PostMapping("/admin/content-tags/{id}/edit")
    @PreAuthorize(MANAGE)
    public String editTag(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam String name,
            @RequestParam(value = "iconFile", required = false) MultipartFile iconFile,
            @RequestParam String description, RedirectAttributes flash) {
        try {
            // 🛡 没传新文件 → 传 null，服务层保留原图标（不是清空）。
            service.editTag(admin.getAdminAccountId(), id, name,
                    icons.uploadOrKeep(iconFile), description);
            flash.addFlashAttribute("notice", "已更新标签");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/content-tags";
    }

    @PostMapping("/admin/content-tags/{id}/retire")
    @PreAuthorize(MANAGE)
    public String retire(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(defaultValue = "true") boolean retired, RedirectAttributes flash) {
        try {
            service.setRetired(admin.getAdminAccountId(), id, retired);
            // ⚠️ 文案要说清"已分配的不受影响" —— 否则运营会以为下线等于全部收回。
            flash.addFlashAttribute("notice", retired
                    ? "已下线该标签：不能再分配，已分配的照旧生效到各自结束时间"
                    : "已重新上线该标签");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/content-tags";
    }

    @PostMapping("/admin/content-tags/assign")
    @PreAuthorize(MANAGE)
    public String assign(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long postId, @RequestParam long tagId,
            @RequestParam String startsAt,
            @RequestParam(required = false) String endsAt,
            RedirectAttributes flash) {
        try {
            // 结束时间留空 = 永久分配。
            Instant to = (endsAt == null || endsAt.isBlank()) ? null : toInstant(endsAt);
            service.assign(admin.getAdminAccountId(), postId, tagId, toInstant(startsAt), to);
            flash.addFlashAttribute("notice", "已打标");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/content-tags";
    }

    @PostMapping("/admin/content-tags/assignments/{id}/remove")
    @PreAuthorize(MANAGE)
    public String unassign(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        boolean removed = service.unassign(admin.getAdminAccountId(), id);
        flash.addFlashAttribute(removed ? "notice" : "error",
                removed ? "已取消打标" : "该分配记录不存在");
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
            throw AppException.validation("时间格式不正确");
        }
    }
}
