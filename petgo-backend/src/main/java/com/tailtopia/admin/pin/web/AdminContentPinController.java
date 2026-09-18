package com.tailtopia.admin.pin.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.pin.service.AdminContentPinService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.content.domain.ContentPin;
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

    /**
     * 推广卡片图本地上传（2026-09-02）。复用种子图片那条上传线：格式白名单（JPG/PNG/WebP、
     * 拒 HEIC）、≤10MB、量宽高出 0.75–1.34 的裁切预判 —— 卡片在 Feed 里就是一张普通内容卡，
     * 约束天然一致。
     */
    private final com.tailtopia.admin.seed.service.AdminSeedImageService images;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminContentPinController(AdminContentPinService service, Messages msg,
            com.tailtopia.admin.seed.service.AdminSeedImageService images) {
        this.service = service;
        this.msg = msg;
        this.images = images;
    }

    @GetMapping("/admin/content-pins")
    @PreAuthorize(VIEW)
    public String list(@RequestParam(value = "slot", required = false) String slot,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "open", required = false) Long open,
            @RequestParam(value = "create", required = false) String create,
            HxRequest hx, Model model) {
        String s = (slot == null || slot.isBlank()) ? ContentPin.SLOT_HOME_FEED : slot;
        model.addAttribute("active", "content-pins");
        model.addAttribute("slot", s);
        // 🛡 本版本只有一个坑位，但界面按「坑位是个下拉」渲染 —— 表上 slot 是普通列、
        //    无 CHECK 约束，将来新增坑位只需多一个取值，不改结构（AD-8 Rule 5）。
        model.addAttribute("slots", java.util.List.of(ContentPin.SLOT_HOME_FEED));
        populate(s, status, page, model);
        model.addAttribute("open", open);
        // ?create=1 深链（无 htmx 时直达新建表单 URL 的落点）：整页渲染后自动展开新建抽屉。
        model.addAttribute("openCreate", create != null);
        // htmx 局部刷新只回表格（摘要条随 oob 一并换）；整页请求回完整视图。
        return hx.isHtmx() ? "admin/fragments/pins-list :: rows(true)" : "admin/content-pins";
    }

    /** 顶置详情抽屉（Story 7.3 · AC3）；非 htmx 直达 → 回列表并自动开该抽屉。 */
    @GetMapping("/admin/content-pins/{id}/drawer")
    @PreAuthorize(VIEW)
    public String drawer(@PathVariable long id, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/content-pins?open=" + id;
        }
        model.addAttribute("active", "content-pins");
        model.addAttribute("d", service.detail(id, Instant.now()));
        return "admin/fragments/drawer-content-pin :: drawer";
    }

    /**
     * 新建顶置表单（Story 7.3 · AC4）：原先常驻页尾的表单收进抽屉，字段与 {@code POST} 的参数逐字不变。
     * 非 htmx 直达 → 回列表并自动展开新建抽屉。
     */
    @GetMapping("/admin/content-pins/new/drawer")
    @PreAuthorize(MANAGE)
    public String newDrawer(@RequestParam(value = "slot", required = false) String slot,
            HxRequest hx, Model model) {
        String s = (slot == null || slot.isBlank()) ? ContentPin.SLOT_HOME_FEED : slot;
        if (!hx.isHtmx()) {
            return "redirect:/admin/content-pins?create=1&slot="
                    + java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        }
        model.addAttribute("active", "content-pins");
        model.addAttribute("slot", s);
        // 首屏候选：与原页尾表单一样，进来就有一批可选内容（不必先搜一次）。
        model.addAttribute("candidates", service.pickable(null, 0));
        model.addAttribute("q", null);
        model.addAttribute("page", 0);
        return "admin/fragments/drawer-content-pin :: createForm";
    }

    private void populate(String slot, String status, int page, Model model) {
        // ⚠️ 整表只读一次、now 只取一次：列表与摘要条共用，两者的状态判定不会在跨秒时打架。
        Instant now = Instant.now();
        var all = service.list(slot, now);
        var found = service.page(all, status, page);
        model.addAttribute("rows", found.rows());
        model.addAttribute("hasNext", found.hasNext());
        model.addAttribute("page", found.page());
        model.addAttribute("status", status);
        model.addAttribute("summary", service.summary(all));
        model.addAttribute("phases", java.util.List.of("ACTIVE", "PENDING", "ENDED"));
    }

    /** 处置成功统一响应（AC5）：抽屉重渲染 + oob 行 + toast + 列表刷新（摘要条与筛选口径都要跟着变）。 */
    private String afterAction(long id, String toast, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        model.addAttribute("d", service.detail(id, Instant.now()));
        model.addAttribute("toast", toast);
        AdminFragmentResponses.trigger(response, AdminHxEvents.PIN_LIST_REFRESH);
        return "admin/fragments/drawer-content-pin :: afterAction";
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
            @RequestParam(value = "promoImageFile", required = false)
            org.springframework.web.multipart.MultipartFile promoImageFile,
            @RequestParam(required = false) String promoTitle,
            @RequestParam(required = false) String promoLinkUrl,
            @RequestParam String startsAt,
            @RequestParam String endsAt,
            HxRequest hx, Model model, jakarta.servlet.http.HttpServletResponse response,
            RedirectAttributes flash) {
        // Story 7.3：抽屉里的新建表单走 htmx —— 成功回新排期的抽屉 + toast + 列表重拉
        //（新行不能用 oob 插：页面上还没有这一行，oob 会被直接丢掉）；
        // 失败（重叠 / 缺必填 / 时间窗非法）不在这里 catch，交给 AdminBusinessExceptionAdvice 出 422 行内 err。
        if (hx.isHtmx()) {
            Instant hFrom = toInstant(startsAt);
            Instant hTo = toInstant(endsAt);
            long id;
            if ("PROMO".equals(objectType)) {
                var up = uploadPromo(promoImageFile, promoImageUrl);
                id = service.createPromoPin(admin.getAdminAccountId(), slot, up.url(),
                        blankToNull(promoTitle), blankToNull(promoLinkUrl), hFrom, hTo);
                if (up.warning() != null) {
                    model.addAttribute("warn", up.warning());
                }
            } else {
                if (contentId == null) {
                    throw AppException.validation("请选择要顶置的内容")
                            .code("admin.err.pins.contentRequired");
                }
                id = service.createContentPin(admin.getAdminAccountId(), slot, contentId, hFrom, hTo);
            }
            return afterAction(id, msg.get("admin.flash.pins.saved"), model, response);
        }
        try {
            Instant from = toInstant(startsAt);
            Instant to = toInstant(endsAt);
            if ("PROMO".equals(objectType)) {
                String imageUrl = blankToNull(promoImageUrl);
                String cropWarning = null;
                // 2026-09-02：本地上传（与 URL 二选一，都给时以上传为准）。
                if (promoImageFile != null && !promoImageFile.isEmpty()) {
                    var uploaded = images.upload(promoImageFile, "pin-promo");
                    imageUrl = uploaded.url();
                    cropWarning = uploaded.warning();
                }
                service.createPromoPin(admin.getAdminAccountId(), slot,
                        imageUrl, blankToNull(promoTitle),
                        blankToNull(promoLinkUrl), from, to);
                // 🛡 比例超出 0.75–1.34 → 保存成功但明说会被裁多少（只提醒，不拦）。
                if (cropWarning != null) {
                    flash.addFlashAttribute("error", cropWarning);
                }
            } else {
                if (contentId == null) {
                    throw AppException.validation("请选择要顶置的内容")
                            .code("admin.err.pins.contentRequired");
                }
                service.createContentPin(admin.getAdminAccountId(), slot, contentId, from, to);
            }
            flash.addFlashAttribute("notice", msg.get("admin.flash.pins.saved"));
        } catch (AppException e) {
            // 重叠 / 缺必填 / 时间窗非法都收在这里回显一句人话，不抛 500。
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/content-pins?slot=" + slot;
    }

    @PostMapping("/admin/content-pins/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam String startsAt, @RequestParam String endsAt,
            HxRequest hx, Model model, jakarta.servlet.http.HttpServletResponse response,
            RedirectAttributes flash) {
        if (hx.isHtmx()) {
            service.reschedule(admin.getAdminAccountId(), id, toInstant(startsAt), toInstant(endsAt));
            return afterAction(id, msg.get("admin.flash.pins.rescheduled"), model, response);
        }
        try {
            service.reschedule(admin.getAdminAccountId(), id, toInstant(startsAt), toInstant(endsAt));
            flash.addFlashAttribute("notice", msg.get("admin.flash.pins.rescheduled"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/content-pins";
    }

    @PostMapping("/admin/content-pins/{id}/terminate")
    @PreAuthorize(MANAGE)
    public String terminate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, jakarta.servlet.http.HttpServletResponse response,
            RedirectAttributes flash) {
        if (hx.isHtmx()) {
            boolean changed = service.terminate(admin.getAdminAccountId(), id, Instant.now());
            return afterAction(id, msg.get(changed ? "admin.flash.pins.terminated"
                    : "admin.flash.pins.alreadyEnded"), model, response);
        }
        try {
            boolean changed = service.terminate(admin.getAdminAccountId(), id, Instant.now());
            flash.addFlashAttribute(changed ? "notice" : "error",
                    msg.get(changed ? "admin.flash.pins.terminated" : "admin.flash.pins.alreadyEnded"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/content-pins";
    }

    /** 推广卡片图：本地上传与 URL 二选一，都给时以上传为准（与整页路径同一份逻辑）。 */
    private UploadedPromo uploadPromo(org.springframework.web.multipart.MultipartFile file, String url) {
        if (file != null && !file.isEmpty()) {
            var uploaded = images.upload(file, "pin-promo");
            return new UploadedPromo(uploaded.url(), uploaded.warning());
        }
        return new UploadedPromo(blankToNull(url), null);
    }

    /** @param warning 比例超出 0.75–1.34 时的提示（🛡 只提醒、不拦） */
    private record UploadedPromo(String url, String warning) {
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
            throw AppException.validation("时间格式不正确").code("admin.err.pins.badTimeFormat");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
