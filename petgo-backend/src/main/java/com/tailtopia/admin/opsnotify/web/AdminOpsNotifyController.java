package com.tailtopia.admin.opsnotify.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.opsnotify.dto.OpsNotifyForm;
import com.tailtopia.admin.opsnotify.service.AdminOpsNotifyService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignStatus;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.ops.target.OpsAppPage;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.shop.domain.ProductCategory;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 运营通知发送页（V1.3.4 AB-31 · FR-138）：发送记录列表（分页）+ 新建抽屉（立即 / 定时）+ 只读详情 + 取消待发送。
 * 结构照运营弹窗页（AB-30 {@code AdminOpsPopupController}）。
 *
 * <p>🔒 <b>权限复用 {@code config.view}（看）/ {@code config.edit}（发、取消）</b>，与 AB-22 / AB-30 同口径，不新增权限码。
 *
 * <p>🔴 发送前必须预估人数：抽屉里「预估人数」按钮调 {@code POST .../preview}，片段带回 N；前端拿 N 填确认框
 * 「将发送给约 N 人」并放开发送钮（N = 0 不放开），人群条件一改就作废、须重新预估（admin-core.js 预估门槛）。
 * 服务端不另存预估结果：新建时表单带回 N（{@code previewCount}），服务端按同一人群重算比对（0 人 / 涨幅过大拒发），
 * 另有一次性 {@code submitKey} 防重复提交（见 {@code AdminOpsNotifyService#create}）。
 *
 * <p>🔴 URL 里一律用 {@code public_token}（D-24）。不提供编辑 / 重发 / 删除；不展示打开 / 点击数（走 PostHog）。
 */
@Controller
public class AdminOpsNotifyController {

    private static final Logger log = LoggerFactory.getLogger(AdminOpsNotifyController.class);

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('config.view') "
            + "or hasAuthority('config.edit')";
    private static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('config.edit')";

    private static final String BASE = "/admin/ops-notifications";

    private final AdminOpsNotifyService service;
    private final OpsCampaignTargetDao targetsDao;
    private final Messages msg;

    public AdminOpsNotifyController(AdminOpsNotifyService service, OpsCampaignTargetDao targetsDao, Messages msg) {
        this.service = service;
        this.targetsDao = targetsDao;
        this.msg = msg;
    }

    /**
     * 列表。抽屉取数复用这同一条 mapping（同 AB-30）：{@code ?create=1}（新建表单）/ {@code ?open=<token>}（只读详情）
     * + HX-Request 返抽屉片段；两者都不带的 htmx 请求返行片段（翻页）；非 htmx 返整页。
     */
    @GetMapping(BASE)
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "open", required = false) String open,
            @RequestParam(value = "create", required = false) String create,
            HxRequest hx, Model model) {
        model.addAttribute("active", "ops-notifications");
        boolean canEdit = has(admin, AdminPermissions.CONFIG_EDIT);
        // 🔒 只读账号不给写入口（新建 / 取消按钮都不渲染）；服务端 @PreAuthorize 照旧兜底。
        model.addAttribute("canEdit", canEdit);
        if (hx.isHtmx() && create != null && canEdit) {
            populateForm(model);
            return "admin/fragments/drawer-ops-notification :: form";
        }
        if (hx.isHtmx() && open != null) {
            populateDetail(service.require(open), model);
            return "admin/fragments/drawer-ops-notification :: detail";
        }
        populateList(page, model);
        model.addAttribute("open", open);
        model.addAttribute("openCreate", create != null && canEdit);
        return hx.isHtmx() ? "admin/fragments/ops-notifications-list :: rows" : "admin/ops-notifications";
    }

    /** 新建表单默认值 + 下拉选项。人群默认「全部用户」：发送前反正要预估、确认框会写明人数。 */
    private void populateForm(Model model) {
        OpsNotifyForm f = new OpsNotifyForm();
        f.setAudienceMode(AudienceSpec.Mode.ALL.name());
        f.setTargetType(OpsTargetType.NONE.name());
        // 一次性提交键：每次打开抽屉一个新的，服务端认领后同一键不能再建第二条（防连点 / 重放）
        f.setSubmitKey(UUID.randomUUID().toString());
        model.addAttribute("form", f);
        model.addAttribute("audienceModes", AudienceSpec.Mode.values());
        model.addAttribute("speciesValues", PetType.values());
        populateTargetOptions(model);
    }

    private static void populateTargetOptions(Model model) {
        model.addAttribute("targetTypes", OpsTargetType.values());
        model.addAttribute("categories", ProductCategory.values());
        model.addAttribute("appPages", OpsAppPage.values());
    }

    /** 只读详情：文案 / 人群 / 跳转 / 时间线 / 计数。IDS 模式另给「指定了多少个 ID」（子表条数，含无效 ID）。 */
    private void populateDetail(OpsNotificationCampaign c, Model model) {
        Map<String, Object> m = row(c, service.creatorNames(List.of(c)));
        m.put("titleEn", c.getTitleEn());
        m.put("bodyId", c.getBodyId());
        m.put("bodyEn", c.getBodyEn());
        m.put("startedAt", c.getStartedAt());
        m.put("finishedAt", c.getFinishedAt());
        m.put("canceledAt", c.getCanceledAt());
        AudienceSpec spec = (AudienceSpec) m.get("spec");
        m.put("idCount", spec != null && spec.mode() == AudienceSpec.Mode.IDS ? targetsDao.countByCampaign(c.getId()) : null);
        model.addAttribute("c", m);
    }

    private void populateList(int page, Model model) {
        Page<OpsNotificationCampaign> p = service.page(page);
        if (p.getNumber() > 0 && p.getNumberOfElements() == 0 && p.getTotalPages() > 0) {
            p = service.page(p.getTotalPages() - 1); // 越界页码（手改 URL / 刚好删空）落回最后一页
        }
        Map<Long, String> creators = service.creatorNames(p.getContent());
        List<Map<String, Object>> view = new ArrayList<>();
        for (OpsNotificationCampaign c : p.getContent()) {
            view.add(row(c, creators));
        }
        model.addAttribute("campaigns", view);
        model.addAttribute("page", p.getNumber());
        model.addAttribute("hasNext", p.hasNext());
        model.addAttribute("total", p.getTotalElements());
    }

    private Map<String, Object> row(OpsNotificationCampaign c, Map<Long, String> creators) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", c.getPublicToken());
        m.put("titleId", c.getTitleId());
        m.put("status", c.getStatus().name());
        m.put("pending", c.getStatus() == OpsCampaignStatus.PENDING);
        m.put("createdAt", c.getCreatedAt());
        m.put("scheduledAt", c.getScheduledAt());
        m.put("recipientCount", c.getRecipientCount());
        m.put("failedCount", c.getFailedCount());
        m.put("skippedCount", c.getSkippedCount());
        m.put("creator", c.getCreatedBy() == null ? null : creators.get(c.getCreatedBy()));
        m.put("targetType", c.getTargetType().name());
        m.put("targetValue", c.getTargetValue());
        AudienceSpec spec;
        try {
            spec = AudienceSpec.parse(c.getAudienceSpec());
        } catch (RuntimeException e) {
            // 库里有 CHECK，理论上到不了；坏了也只影响这一格显示，不让整页 500（只记 token，人群 JSON 不进日志）
            log.warn("运营通知人群规格解析失败，该格显示为空: token={} err={}", c.getPublicToken(),
                    e.getClass().getSimpleName());
            spec = null;
        }
        m.put("spec", spec);
        m.put("mode", spec == null ? null : spec.mode().name());
        return m;
    }

    /**
     * 人数预估：返回带 N 的片段（落进抽屉里的预估槽）。人群条件不合法 → 422 由 AdminBusinessExceptionAdvice
     * 按 HX-Target 回填到同一个槽（带错误码，标红对应字段）。
     */
    @PostMapping(BASE + "/preview")
    @PreAuthorize(EDIT_AUTH)
    public String preview(@ModelAttribute("form") OpsNotifyForm form, Model model) {
        model.addAttribute("count", service.preview(form));
        return "admin/fragments/drawer-ops-notification :: preview";
    }

    // ---------- 写入 ----------
    // htmx 分支不 catch：AppException 交给 AdminBusinessExceptionAdvice 出 4xx 片段（落回 HX-Target 那个错误槽）。
    // 非 htmx 分支本地 catch 转 flash（GlobalExceptionHandler 会把 RFC 9457 裸 JSON 甩给运营）。

    @PostMapping(BASE)
    @PreAuthorize(EDIT_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @ModelAttribute("form") OpsNotifyForm form, HxRequest hx, Model model,
            HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.create(form, admin.getAdminAccountId());
            AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_CLOSE);
            return done(msg.get("admin.flash.ops-notify.created"), 0, admin, model);
        }
        try {
            service.create(form, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.ops-notify.created"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    /**
     * 取消待发送。行内按钮的 hx-target 是列表上方的 {@code #ops-notify-row-error}（同 AB-30 行内启停）：
     * 成功清空该槽 + 整表 oob（停在当前页）；已在发送 / 已完成 → 409 回填到同一个槽。
     */
    @PostMapping(BASE + "/{token}/cancel")
    @PreAuthorize(EDIT_AUTH)
    public String cancel(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(value = "page", defaultValue = "0") int page,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.cancel(token, admin.getAdminAccountId());
            return done(msg.get("admin.flash.ops-notify.canceled"), page, admin, model);
        }
        try {
            service.cancel(token, admin.getAdminAccountId());
            ra.addFlashAttribute("notice", msg.get("admin.flash.ops-notify.canceled"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    /** 成功：整表 oob 重拉 + toast。主 swap 为空（顺带清掉上一次的行内报错 / 抽屉错误槽）。 */
    private String done(String message, int page, AdminUserDetails admin, Model model) {
        populateList(page, model);
        // 行内取消按钮按 canEdit 渲染 —— 漏了它 th:if="${canEdit and …}" 取到 null 直接 500
        model.addAttribute("canEdit", has(admin, AdminPermissions.CONFIG_EDIT));
        model.addAttribute("message", message);
        return "admin/fragments/ops-notifications-list :: done";
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
