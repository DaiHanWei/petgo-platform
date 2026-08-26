package com.tailtopia.admin.pin.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.pin.service.AdminContentPinService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.content.domain.ContentPin;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 顶置管理（Story 11.1 · AB-10A）。
 *
 * <h2>🛡 双权限码</h2>
 * 查看 {@code content.pin_view} / 编辑 {@code content.pin_manage}。
 * ⚠️ <b>侧栏 {@code sec:authorize} 的表达式必须与这里的 {@code @PreAuthorize} 逐字一致</b> ——
 * 两边走散时的表现最难查：权限放行了、直接敲 URL 能进，但侧栏里没有这个链接，
 * 运营只会得出「我没有这个功能」，而日志、403、报错一概没有。
 *
 * <h2>🕗 时区</h2>
 * 表单里填的是 <b>WIB 墙上时间</b>（`datetime-local` 无时区信息），入库转 UTC 绝对时刻。
 * 界面须在输入框旁明示「WIB」 —— 不写运营就会按自己电脑的时区填，整批排期偏移。
 */
@Controller
public class AdminContentPinController {

    static final String VIEW = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.CONTENT_PIN_VIEW + "')";
    static final String MANAGE = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.CONTENT_PIN_MANAGE + "')";

    /** 运营填的墙上时间按这个时区解释（AD-9 Rule 4）。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final AdminContentPinService service;

    public AdminContentPinController(AdminContentPinService service) {
        this.service = service;
    }

    @GetMapping("/admin/content-pins")
    @PreAuthorize(VIEW)
    public String list(@RequestParam(value = "slot", required = false) String slot, Model model) {
        String s = (slot == null || slot.isBlank()) ? ContentPin.SLOT_HOME_FEED : slot;
        model.addAttribute("active", "content-pins");
        model.addAttribute("slot", s);
        // 🛡 本版本只有一个坑位，但界面按「坑位是个下拉」渲染 —— 表上 slot 是普通列、
        //    无 CHECK 约束，将来新增坑位只需多一个取值，不改结构（AD-8 Rule 5）。
        model.addAttribute("slots", java.util.List.of(ContentPin.SLOT_HOME_FEED));
        model.addAttribute("rows", service.list(s, Instant.now()));
        return "admin/content-pins";
    }

    /** 内容选择器（HTMX 局部）：只返回可公开展示的内容，分页。 */
    @GetMapping("/admin/content-pins/pick")
    @PreAuthorize(VIEW)
    public String pick(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        model.addAttribute("candidates", service.pickable(q, page));
        model.addAttribute("q", q);
        model.addAttribute("page", page);
        return "admin/content-pins :: candidates";
    }

    @PostMapping("/admin/content-pins")
    @PreAuthorize(MANAGE)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String slot,
            @RequestParam String objectType,
            @RequestParam(required = false) Long contentId,
            @RequestParam(required = false) String promoImageUrl,
            @RequestParam(required = false) String promoTitle,
            @RequestParam(required = false) String promoLinkUrl,
            @RequestParam String startsAt,
            @RequestParam String endsAt,
            RedirectAttributes flash) {
        try {
            Instant from = toInstant(startsAt);
            Instant to = toInstant(endsAt);
            if ("PROMO".equals(objectType)) {
                service.createPromoPin(admin.getAdminAccountId(), slot,
                        blankToNull(promoImageUrl), blankToNull(promoTitle),
                        blankToNull(promoLinkUrl), from, to);
            } else {
                if (contentId == null) {
                    throw AppException.validation("请选择要顶置的内容");
                }
                service.createContentPin(admin.getAdminAccountId(), slot, contentId, from, to);
            }
            flash.addFlashAttribute("notice", "已保存顶置排期");
        } catch (AppException e) {
            // 重叠 / 缺必填 / 时间窗非法都收在这里回显一句人话，不抛 500。
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/content-pins?slot=" + slot;
    }

    @PostMapping("/admin/content-pins/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam String startsAt, @RequestParam String endsAt,
            RedirectAttributes flash) {
        try {
            service.reschedule(admin.getAdminAccountId(), id, toInstant(startsAt), toInstant(endsAt));
            flash.addFlashAttribute("notice", "已更新排期时间");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/content-pins";
    }

    @PostMapping("/admin/content-pins/{id}/terminate")
    @PreAuthorize(MANAGE)
    public String terminate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            boolean changed = service.terminate(admin.getAdminAccountId(), id, Instant.now());
            flash.addFlashAttribute(changed ? "notice" : "error",
                    changed ? "已提前结束该顶置" : "该排期已结束，无需再操作");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/content-pins";
    }

    /**
     * `datetime-local` 的值（`yyyy-MM-ddTHH:mm`，无时区）按 WIB 解释成绝对时刻。
     *
     * <p>⚠️ 绝不能用 {@code Instant.parse} 或系统默认时区 —— 前者格式对不上，
     * 后者会让"服务器在哪"决定运营排期的实际生效时刻。
     */
    private static Instant toInstant(String localDateTime) {
        try {
            return LocalDateTime.parse(localDateTime).atZone(WIB).toInstant();
        } catch (RuntimeException e) {
            throw AppException.validation("时间格式不正确");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
