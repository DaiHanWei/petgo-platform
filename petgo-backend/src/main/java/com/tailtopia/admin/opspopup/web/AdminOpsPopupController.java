package com.tailtopia.admin.opspopup.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.opspopup.dto.OpsPopupForm;
import com.tailtopia.admin.opspopup.service.AdminOpsPopupService;
import com.tailtopia.admin.seed.dto.UploadedImage;
import com.tailtopia.admin.seed.service.AdminSeedImageService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupAudience;
import com.tailtopia.ops.popup.domain.OpsPopupFrequency;
import com.tailtopia.ops.popup.domain.OpsPopupStatus;
import com.tailtopia.ops.popup.service.OpsPopupCandidateService;
import com.tailtopia.ops.target.OpsAppPage;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.shop.domain.ProductCategory;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
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
 * 运营弹窗后台配置页（V1.3.4 AB-30 · FR-137）：列表 + 抽屉（新建 / 编辑）+ 行内启用 / 停用。
 * 结构照 Toko 顶部 Banner 页（{@code AdminShopBannerController}）。
 *
 * <p>🔒 <b>权限复用 {@code config.view} / {@code config.edit}</b>，与 AB-22（App 版本配置卡）同口径，不新增权限码。
 * 查看门放 view 或 edit 任一（只授了 edit 的人也得进得来改），写端点只认 edit。
 *
 * <p>🔴 URL 里一律用 {@code public_token}，不外露自增 id（护栏）。
 * 🔴 不提供删除：删除会级联掉曝光记录，频次失忆后同一用户可能再看到一次。停用即可。
 */
@Controller
public class AdminOpsPopupController {

    private static final Logger log = LoggerFactory.getLogger(AdminOpsPopupController.class);

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('config.view') "
            + "or hasAuthority('config.edit')";
    private static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('config.edit')";

    /** 单张图上限 1MB（spec AB-30）：弹窗是冷启动首屏，图大了弱网下弹窗比首页还慢。 */
    static final long MAX_IMAGE_BYTES = 1024L * 1024;

    private static final String BASE = "/admin/ops-popups";

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
     * 返表单片段；两者都不带的 htmx 请求返行片段；非 htmx 返整页。
     *
     * <p>只读账号也能 {@code ?open=} 看详情：表单以 {@code canEdit=false} 渲染成全部禁用、无保存钮。
     */
    @GetMapping(BASE)
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "open", required = false) String open,
            @RequestParam(value = "create", required = false) String create,
            HxRequest hx, Model model) {
        model.addAttribute("active", "ops-popups");
        boolean canEdit = has(admin, AdminPermissions.CONFIG_EDIT);
        // 🔒 只读账号不给写入口（新建 / 编辑 / 启停按钮都不渲染）；服务端 @PreAuthorize 照旧兜底。
        model.addAttribute("canEdit", canEdit);
        if (hx.isHtmx() && create != null && canEdit) {
            populateForm(null, model);
            return "admin/fragments/drawer-ops-popup :: form";
        }
        if (hx.isHtmx() && open != null) {
            populateForm(service.require(open), model);
            return "admin/fragments/drawer-ops-popup :: form";
        }
        populateList(model);
        model.addAttribute("open", open);
        model.addAttribute("openCreate", create != null && canEdit);
        return hx.isHtmx() ? "admin/fragments/ops-popups-list :: rows" : "admin/ops-popups";
    }

    /** 新建（{@code p == null}）/ 编辑态回填 + 下拉选项。 */
    private void populateForm(OpsPopup p, Model model) {
        OpsPopupForm f = new OpsPopupForm();
        Map<String, Object> existing = new LinkedHashMap<>();
        if (p == null) {
            f.setPriority("0");
            f.setAudience(OpsPopupAudience.ALL.name());
            f.setNewUserDays("7");
            f.setFrequency(OpsPopupFrequency.ONCE.name());
            f.setTargetType(OpsTargetType.NONE.name());
            // 默认开始时间 = 此刻（WIB）：运营最常见的是「现在就开始」，空着反而要多点一次日历。
            f.setStartsAt(adminTime.nowWibForInput());
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
        }
        // ⚠️ 库里存的是 objectKey，直接塞 <img> 显示不出来；拼不出 URL（CDN 未配）就当没有已存图 ——
        //    key 仍在隐藏字段里，不换图照样保留（同 Banner 抽屉）。
        existing.put("id", existingImage(f.getImageUrlId()));
        existing.put("en", existingImage(f.getImageUrlEn()));
        model.addAttribute("form", f);
        model.addAttribute("popupToken", p == null ? null : p.getPublicToken());
        model.addAttribute("popupStatus", p == null ? null : p.getStatus().name());
        model.addAttribute("existingImages", existing);
        model.addAttribute("audiences", OpsPopupAudience.values());
        model.addAttribute("frequencies", OpsPopupFrequency.values());
        model.addAttribute("targetTypes", OpsTargetType.values());
        model.addAttribute("categories", ProductCategory.values());
        model.addAttribute("appPages", OpsAppPage.values());
        model.addAttribute("maxImageBytes", MAX_IMAGE_BYTES);
    }

    private Map<String, String> existingImage(String raw) {
        String url = candidates.resolveImage(raw);
        return url == null ? null : Map.of("key", raw, "url", url);
    }

    private void populateList(Model model) {
        Instant now = Instant.now();
        List<Map<String, Object>> view = new ArrayList<>();
        for (OpsPopup p : service.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token", p.getPublicToken());
            m.put("name", p.getName());
            // 缩略图与 App 同一套换算（https 原样 / objectKey 拼 CDN；拼不出 = null → 模板显示提示而不是裂图）
            m.put("imageUrl", candidates.resolveImage(p.getImageUrlId()));
            m.put("hasEnImage", p.getImageUrlEn() != null);
            m.put("active", p.getStatus() == OpsPopupStatus.ACTIVE);
            // 未开始 / 进行中 / 已结束：由时间窗现算，与 App 候选过滤同一份判定（ScheduleWindow）
            m.put("phase", ScheduleWindow.phaseAt(now, p.getStartsAt(), p.getEndsAt()).name());
            m.put("startsAt", p.getStartsAt());
            m.put("endsAt", p.getEndsAt());
            m.put("priority", p.getPriority());
            m.put("audience", p.getAudience().name());
            m.put("newUserDays", p.getNewUserDays());
            m.put("frequency", p.getFrequency().name());
            m.put("targetType", p.getTargetType().name());
            m.put("targetValue", p.getTargetValue());
            view.add(m);
        }
        model.addAttribute("popups", view);
    }

    /**
     * 弹窗图直传：复用 {@link AdminSeedImageService}（同一条上传链路，目录 {@code ops-popup}），不新开通道。
     *
     * <p>🔴 <b>&gt;1MB 在这里先拒、不发 OSS</b>：AdminSeedImageService 的上限是 10MB（内容图口径），
     * 放过去就是一张 9MB 的弹窗图进了公开桶。前端 {@code data-max-bytes} 也会先拦，但那只是体验护栏。
     *
     * <p>不回传裁切预判文案：那是首页信息流（0.75–1.34 比例）的口径，弹窗是 4:5 固定框，套用只会误导。
     */
    @PostMapping(BASE + "/images")
    @PreAuthorize(EDIT_AUTH)
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
    // htmx 分支不 catch：AppException 交给 AdminBusinessExceptionAdvice 出 422 片段（落回抽屉里的错误槽）。
    // 非 htmx 分支本地 catch 转 flash（GlobalExceptionHandler 会把 RFC 9457 裸 JSON 甩给运营）。

    @PostMapping(BASE)
    @PreAuthorize(EDIT_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @ModelAttribute("form") OpsPopupForm form, HxRequest hx, Model model,
            HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.create(form, admin.getAdminAccountId());
            return done(msg.get("admin.flash.opsPopup.created"), admin, model, response);
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
    @PreAuthorize(EDIT_AUTH)
    public String update(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @ModelAttribute("form") OpsPopupForm form, HxRequest hx, Model model,
            HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.update(token, form, admin.getAdminAccountId());
            return done(msg.get("admin.flash.opsPopup.updated"), admin, model, response);
        }
        try {
            service.update(token, form, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.opsPopup.updated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    @PostMapping(BASE + "/{token}/activate")
    @PreAuthorize(EDIT_AUTH)
    public String activate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.activate(token, admin.getAdminAccountId());
            return toggled(msg.get("admin.flash.opsPopup.activated"), admin, model);
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
    @PreAuthorize(EDIT_AUTH)
    public String deactivate(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.deactivate(token, admin.getAdminAccountId());
            return toggled(msg.get("admin.flash.opsPopup.deactivated"), admin, model);
        }
        try {
            service.deactivate(token, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.opsPopup.deactivated"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    /**
     * 处置成功：整表 oob 重拉 + toast + 关抽屉（同 Banner 页）。整表而不是换一行 —— 改优先级会改变行序。
     */
    private String done(String message, AdminUserDetails admin, Model model, HttpServletResponse response) {
        AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_CLOSE);
        return toggled(message, admin, model);
    }

    /**
     * 行内启用 / 停用成功：整表 oob + toast，<b>不碰抽屉</b>。
     *
     * <p>🔴 行按钮的 hx-target 是列表页上方的 {@code #ops-popup-row-error}（不是抽屉体）：运营可能正开着另一条的抽屉
     * 改到一半，这里若发 drawer-close / 清抽屉体，那份没保存的编辑就没了。主 swap 是空内容 —— 顺带清掉上一次的行内报错；
     * 失败（404 / 422）由 AdminBusinessExceptionAdvice 按 HX-Target 回填到同一个槽，运营看得见。
     */
    private String toggled(String message, AdminUserDetails admin, Model model) {
        populateList(model);
        // 行内按钮按 canEdit 渲染 —— 漏了它 th:if="${canEdit and …}" 取到 null 直接 500
        model.addAttribute("canEdit", has(admin, AdminPermissions.CONFIG_EDIT));
        model.addAttribute("message", message);
        return "admin/fragments/ops-popups-list :: done";
    }

    private static boolean has(AdminUserDetails admin, String permission) {
        if (admin == null) {
            return false;
        }
        for (GrantedAuthority a : admin.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority()) || permission.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
