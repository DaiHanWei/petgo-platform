package com.tailtopia.admin.opspopup.web;

import com.tailtopia.admin.opspopup.dto.OpsPopupForm;
import com.tailtopia.admin.opspopup.service.AdminOpsPopupService;
import com.tailtopia.admin.opspopup.service.OpsPopupState;
import com.tailtopia.admin.opspopup.service.OpsPopupValidationException;
import com.tailtopia.admin.seed.dto.UploadedImage;
import com.tailtopia.admin.seed.service.AdminSeedImageService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.ops.audience.ActivityBand;
import com.tailtopia.ops.audience.AudienceFilters;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupAudience;
import com.tailtopia.ops.popup.domain.OpsPopupFrequency;
import com.tailtopia.ops.popup.service.OpsPopupCandidateService;
import com.tailtopia.ops.target.OpsAppPage;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.shop.domain.ProductCategory;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 运营弹窗后台配置页（V1.3.4 AB-30 · FR-137；按后台返工清单 §3 对齐 UI 稿 H1–H10）：
 * 列表（状态筛选 + 名称搜索 + 四格汇总）+ 抽屉（新建 / 编辑 / 预览）+ 行内 停用 / 启用 / 删除（服务端确认弹层 H8–H10）。
 *
 * <p>🔒 <b>权限 {@code ops_popup.manage}</b>（返工清单 §1：查看 + 编辑同一码，不预授予预置角色，超管隐式拥有）。
 * 单一码之后不再有只读态（抽屉不再渲染禁用表单）。
 *
 * <p>🔴 URL 里一律用 {@code public_token}，不外露自增 id（护栏）。
 */
@Controller
public class AdminOpsPopupController {

    private static final Logger log = LoggerFactory.getLogger(AdminOpsPopupController.class);

    /** 查看即管理（返工清单 §1）：单一码 {@code ops_popup.manage}，无此码侧栏不出现、所有端点 403。 */
    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('ops_popup.manage')";

    /** 单张图上限 1MB（spec AB-30）：弹窗是冷启动首屏，图大了弱网下弹窗比首页还慢。 */
    static final long MAX_IMAGE_BYTES = 1024L * 1024;

    private static final String BASE = "/admin/ops-popups";
    /** 列表「生效时间」短格式（H1：{@code 10/10 00:00 – 10/13 23:59}，WIB）。 */
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(AdminTime.ZONE);
    /** 抽屉里贴错的槽（与模板 drawer-ops-popup 的 {@code opsp-err-<slot>} 一一对应；422 时全部 oob 刷一遍）。 */
    static final List<String> ERROR_SLOTS = List.of("name", "image", "title", "body", "button", "target",
            "window", "frequency", "audience", "filters", "priority");

    private final AdminOpsPopupService service;
    private final OpsPopupCandidateService candidates;
    private final AdminSeedImageService images;
    private final AdminTime adminTime;
    private final Messages msg;

    public AdminOpsPopupController(AdminOpsPopupService service, OpsPopupCandidateService candidates,
            AdminSeedImageService images, AdminTime adminTime, Messages msg) {
        this.service = service;
        this.candidates = candidates;
        this.images = images;
        this.adminTime = adminTime;
        this.msg = msg;
    }

    /**
     * 列表。抽屉取数复用这同一条 mapping（同 Banner 页）：{@code ?create=1} / {@code ?open=<token>} + HX-Request
     * 返表单片段；两者都不带的 htmx 请求（筛选栏）返列表片段；非 htmx 返整页。
     */
    @GetMapping(BASE)
    @PreAuthorize(AUTH)
    public String list(@RequestParam(value = "open", required = false) String open,
            @RequestParam(value = "create", required = false) String create,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            HxRequest hx, Model model) {
        model.addAttribute("active", "ops-popups");
        if (hx.isHtmx() && create != null) {
            populateForm(null, model);
            return "admin/fragments/drawer-ops-popup :: form";
        }
        if (hx.isHtmx() && open != null) {
            populateForm(service.require(open), model);
            return "admin/fragments/drawer-ops-popup :: form";
        }
        populateList(model, status, q);
        model.addAttribute("open", open);
        model.addAttribute("openCreate", create != null);
        return hx.isHtmx() ? "admin/fragments/ops-popups-list :: rows" : "admin/ops-popups";
    }

    /** 新建（{@code p == null}）/ 编辑态回填 + 选项。新建按 UI 稿 H3 / H4：时间、优先级、跳转类型都空着，频次默认每天一次、人群默认全部。 */
    private void populateForm(OpsPopup p, Model model) {
        OpsPopupForm f = new OpsPopupForm();
        Map<String, Object> existing = new LinkedHashMap<>();
        if (p == null) {
            f.setAudience(OpsPopupAudience.ALL.name());
            f.setNewUserDays("7");
            f.setFrequency(OpsPopupFrequency.DAILY.name());
        } else {
            f.setName(p.getName());
            f.setPriority(String.valueOf(p.getPriority()));
            f.setStartsAt(adminTime.wibForInput(p.getStartsAt()));
            f.setEndsAt(adminTime.wibForInput(p.getEndsAt()));
            f.setAudience(p.getAudience().name());
            f.setNewUserDays(String.valueOf(p.getNewUserDays()));
            f.setFrequency(p.getFrequency().name());
            f.setImageUrlId(p.getImageUrlId());
            f.setImageUrlEn(p.getImageUrlEn());
            f.setTitleId(p.getTitleId());
            f.setTitleEn(p.getTitleEn());
            f.setBodyId(p.getBodyId());
            f.setBodyEn(p.getBodyEn());
            f.setButtonTextId(p.getButtonTextId());
            f.setButtonTextEn(p.getButtonTextEn());
            f.setTargetType(p.getTargetType().name());
            f.setTargetValue(p.getTargetValue());
            AudienceFilters af = p.getAudienceFilters();
            f.setLevelTier(first(af.levelTiers()));
            f.setSpecies(first(af.species()));
            f.setActivity(first(af.activity()));
            f.setPaid(af.paid() == null ? null : af.paid().toString());
        }
        // ⚠️ 库里存的是 objectKey，直接塞 <img> 显示不出来；拼不出 URL（CDN 未配）就不出缩略图 ——
        //    key 仍在隐藏字段里，不换图照样保留（同 Banner 抽屉）。
        existing.put("id", candidates.resolveImage(f.getImageUrlId()));
        existing.put("en", candidates.resolveImage(f.getImageUrlEn()));
        model.addAttribute("form", f);
        model.addAttribute("popupToken", p == null ? null : p.getPublicToken());
        model.addAttribute("existingImages", existing);
        model.addAttribute("targetTypes", OpsTargetType.values());
        model.addAttribute("categories", ProductCategory.values());
        model.addAttribute("appPages", OpsAppPage.values());
        model.addAttribute("maxImageBytes", MAX_IMAGE_BYTES);
        model.addAttribute("levelTierValues", GrowthTier.values());
        model.addAttribute("speciesValues", PetType.values());
        model.addAttribute("activityValues", ActivityBand.values());
    }

    /** 后台是单选下拉：取集合里的那一个（集合为多选留的余地，目前最多一个）。 */
    private static String first(java.util.Set<? extends Enum<?>> s) {
        return s == null || s.isEmpty() ? null : s.iterator().next().name();
    }

    /** 列表「目标人群」下方的条件摘要：「1–4 级 · 猫 · 7 天内打开过 App · 付过钱」；不筛 = null。 */
    private String filtersSummary(AudienceFilters f) {
        if (f.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (f.levelTiers() != null) {
            f.levelTiers().forEach(t -> parts.add(msg.get("admin.opsNotify.levelTier." + t.name())));
        }
        if (f.species() != null) {
            f.species().forEach(t -> parts.add(msg.get("admin.opsNotify.species." + t.name())));
        }
        if (f.activity() != null) {
            f.activity().forEach(a -> parts.add(msg.get("admin.opsNotify.activity." + a.name())));
        }
        if (f.paid() != null) {
            parts.add(msg.get("admin.opsNotify.paid." + f.paid()));
        }
        return String.join(" · ", parts);
    }

    private void populateList(Model model, String status, String q) {
        Instant now = Instant.now();
        OpsPopupState filter = OpsPopupState.parseOrNull(status);
        Map<OpsPopupState, Long> counts = service.counts(now);
        List<Map<String, Object>> view = new ArrayList<>();
        for (OpsPopup p : service.list(filter, q, now)) {
            OpsPopupState state = OpsPopupState.of(p, now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token", p.getPublicToken());
            m.put("name", p.getName());
            // 缩略图与 App 同一套换算（https 原样 / objectKey 拼 CDN；拼不出 = null → 灰底占位而不是裂图）
            m.put("imageUrl", candidates.resolveImage(p.getImageUrlId()));
            m.put("state", state.name());
            m.put("window", window(p));
            m.put("priority", p.getPriority());
            m.put("audience", p.getAudience().name());
            m.put("newUserDays", p.getNewUserDays());
            m.put("filters", filtersSummary(p.getAudienceFilters()));
            m.put("frequency", p.getFrequency().name());
            // 行内链接按状态（H1）：编辑恒有；停用 = 进行中 / 未开始；启用 = 已停用；删除 = 未开始且从未曝光（§0-2）
            m.put("canDeactivate", state == OpsPopupState.RUNNING || state == OpsPopupState.PENDING);
            m.put("canActivate", state == OpsPopupState.DISABLED);
            m.put("canDelete", state == OpsPopupState.PENDING && service.deletable(p, now));
            view.add(m);
        }
        model.addAttribute("popups", view);
        model.addAttribute("counts", counts);
        model.addAttribute("totalCount", counts.values().stream().mapToLong(Long::longValue).sum());
        model.addAttribute("states", OpsPopupState.values());
        model.addAttribute("filterStatus", filter == null ? "" : filter.name());
        model.addAttribute("q", q == null ? "" : q.trim());
    }

    /** {@code 10/10 00:00 – 10/13 23:59}；历史数据无结束时间时后半段显示「长期有效」。 */
    private String window(OpsPopup p) {
        String end = p.getEndsAt() == null ? msg.get("admin.opsPopups.noEnd") : SHORT.format(p.getEndsAt());
        return SHORT.format(p.getStartsAt()) + " – " + end;
    }

    /**
     * 弹窗图直传：复用 {@link AdminSeedImageService}（同一条上传链路，目录 {@code ops-popup}），不新开通道。
     *
     * <p>🔴 <b>&gt;1MB 在这里先拒、不发 OSS</b>：AdminSeedImageService 的上限是 10MB（内容图口径），
     * 放过去就是一张 9MB 的弹窗图进了公开桶。前端也会先拦（UI 稿 H5），但那只是体验护栏。
     */
    @PostMapping(BASE + "/images")
    @PreAuthorize(AUTH)
    @ResponseBody
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.getSize() > MAX_IMAGE_BYTES) {
            long kb = (file.getSize() + 1023) / 1024;
            return ResponseEntity.badRequest()
                    .body(Map.of("error", msg.get("admin.err.opsPopup.imageTooLarge", kb)));
        }
        try {
            UploadedImage u = images.upload(file, "ops-popup");
            return ResponseEntity.ok(new UploadedImage(u.url(), u.w(), u.h(), null, u.objectKey(), u.sizeBytes()));
        } catch (AppException e) {
            return ResponseEntity.badRequest().body(Map.of("error", msg.resolve(e)));
        } catch (Exception e) {
            // 只记异常类型与大小：文件名可能带个人信息，异常消息里可能夹着带签名的 OSS 地址，一律不进日志
            log.warn("ops-popup image upload failed: {} (size={}B)", e.getClass().getSimpleName(), file.getSize());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", msg.get("admin.shop.form.uploadFailed")));
        }
    }

    // ---------- 写入 ----------
    // 抽屉保存：字段错误一次全回（H7）—— 本地 catch OpsPopupValidationException 出 422 汇总片段（逐槽 oob 贴错 + 汇总条）；
    // 其余 AppException（如 404）交给 AdminBusinessExceptionAdvice 出 422 / 404 行内片段。
    // 非 htmx 分支本地 catch 转 flash（GlobalExceptionHandler 会把 RFC 9457 裸 JSON 甩给运营）。

    @PostMapping(BASE)
    @PreAuthorize(AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @ModelAttribute("form") OpsPopupForm form,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            try {
                service.create(form, admin.getAdminAccountId());
            } catch (OpsPopupValidationException e) {
                return fieldErrors(e, model, response);
            }
            AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_CLOSE);
            return done(msg.get("admin.flash.opsPopup.created"), model, status, q);
        }
        try {
            service.create(form, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.opsPopup.created"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    @PostMapping(BASE + "/{token}")
    @PreAuthorize(AUTH)
    public String update(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @ModelAttribute("form") OpsPopupForm form,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            try {
                service.update(token, form, admin.getAdminAccountId());
            } catch (OpsPopupValidationException e) {
                return fieldErrors(e, model, response);
            }
            AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_CLOSE);
            return done(msg.get("admin.flash.opsPopup.updated"), model, status, q);
        }
        try {
            service.update(token, form, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.opsPopup.updated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    /**
     * 422 汇总片段（H7）：主 swap 落进表单底部的汇总槽（「还有 N 项需要修改」），各字段的错误文案以 oob 贴到
     * {@code #opsp-err-<slot>}（没错的槽一并清空：上一次的红字不能残留），要标红的输入名单挂在 {@code data-invalid}。
     */
    private String fieldErrors(OpsPopupValidationException e, Model model, HttpServletResponse response) {
        Map<String, List<String>> bySlot = new LinkedHashMap<>();
        ERROR_SLOTS.forEach(s -> bySlot.put(s, new ArrayList<>()));
        Set<String> invalid = new LinkedHashSet<>();
        for (OpsPopupValidationException.FieldError fe : e.errors()) {
            bySlot.computeIfAbsent(fe.slot(), k -> new ArrayList<>()).add(msg.get(fe.code(), fe.args()));
            invalid.addAll(fe.inputs());
        }
        model.addAttribute("errorSlots", bySlot);
        model.addAttribute("invalidInputs", String.join(",", invalid));
        model.addAttribute("errorCodes", e.errors().stream().map(OpsPopupValidationException.FieldError::code)
                .distinct().toList());
        model.addAttribute("summary", msg.get("admin.opsPopups.form.errorSummary", e.slotCount()));
        response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        return "admin/fragments/drawer-ops-popup :: errors";
    }

    // ---------- 行内 停用 / 启用 / 删除：先拉服务端确认弹层（H8 / H9 / H10），弹层内再 POST ----------

    /**
     * 确认弹层。行内链接 {@code hx-get} 到这里（hx-target = 列表上方的行内错误槽：拉不到（404 / 删不了 409）时红字落在那里），
     * 成功则 HX-Retarget 到页面 {@code #confirm-host}。弹层表单带上当前筛选（{@code status} / {@code q}），处置后整表按原筛选重拉。
     */
    @GetMapping(BASE + "/{token}/confirm/{action}")
    @PreAuthorize(AUTH)
    public String confirm(@PathVariable String token, @PathVariable String action,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:" + BASE;
        }
        OpsPopup p = service.require(token);
        Map<String, String> hidden = new LinkedHashMap<>();
        hidden.put("status", status == null ? "" : status);
        hidden.put("q", q == null ? "" : q);
        String path = BASE + "/" + token + "/";
        AdminConfirmModal cm = switch (action) {
            case "deactivate" -> new AdminConfirmModal(
                    msg.get("admin.opsPopups.confirm.deactivateTitle", p.getName()), null, List.of(),
                    msg.get("admin.opsPopups.confirm.deactivateBody"), null, null, null,
                    path + "deactivate", hidden, msg.get("admin.opsPopups.btn.deactivate"), true);
            case "activate" -> new AdminConfirmModal(
                    msg.get("admin.opsPopups.confirm.activateTitle", p.getName()), null, List.of(),
                    msg.get("admin.opsPopups.confirm.activateBody"), null, null, null,
                    path + "activate", hidden, msg.get("admin.opsPopups.btn.activate"), false);
            case "delete" -> {
                if (!service.deletable(p, Instant.now())) {
                    throw AppException.conflict("只有未开始的弹窗可以删除").code("admin.err.opsPopup.deleteNotPending");
                }
                yield new AdminConfirmModal(
                        msg.get("admin.opsPopups.confirm.deleteTitle", p.getName()), null, List.of(),
                        msg.get("admin.opsPopups.confirm.deleteBody"), null, null, null,
                        path + "delete", hidden, msg.get("admin.opsPopups.btn.delete"), true);
            }
            default -> throw AppException.notFound("未知操作").code("admin.err.opsPopup.notFound");
        };
        model.addAttribute("cm", cm);
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#confirm-host");
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
        return "admin/fragments/confirm-modal :: modal";
    }

    @PostMapping(BASE + "/{token}/activate")
    @PreAuthorize(AUTH)
    public String activate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.activate(token, admin.getAdminAccountId());
            return confirmed(msg.get("admin.flash.opsPopup.activated"), model, response, status, q);
        }
        try {
            service.activate(token, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.opsPopup.activated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    @PostMapping(BASE + "/{token}/deactivate")
    @PreAuthorize(AUTH)
    public String deactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.deactivate(token, admin.getAdminAccountId());
            return confirmed(msg.get("admin.flash.opsPopup.deactivated"), model, response, status, q);
        }
        try {
            service.deactivate(token, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.opsPopup.deactivated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    @PostMapping(BASE + "/{token}/delete")
    @PreAuthorize(AUTH)
    public String delete(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.delete(token, admin.getAdminAccountId());
            return confirmed(msg.get("admin.flash.opsPopup.deleted"), model, response, status, q);
        }
        try {
            service.delete(token, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.opsPopup.deleted"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    /**
     * 弹层内处置成功：整表 oob（按原筛选）+ toast，swap 之后发 {@code admin:confirm-close} 收弹层，<b>不碰抽屉</b>
     * （运营可能正开着另一条的抽屉改到一半）。失败（404 / 409）由 AdminBusinessExceptionAdvice 回填弹层内的错误槽。
     */
    private String confirmed(String message, Model model, HttpServletResponse response, String status, String q) {
        // 在 swap 之后再收弹层：先收的话发请求的表单已不在 DOM 里
        response.setHeader("HX-Trigger-After-Swap", AdminHxEvents.CONFIRM_CLOSE);
        return done(message, model, status, q);
    }

    /** 处置成功：整表 oob 重拉（改优先级 / 状态会改变行序与汇总）+ toast。 */
    private String done(String message, Model model, String status, String q) {
        populateList(model, status, q);
        model.addAttribute("message", message);
        return "admin/fragments/ops-popups-list :: done";
    }
}
