package com.tailtopia.admin.growth.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.growth.service.AdminGrowthConfigService;
import com.tailtopia.admin.growth.service.AdminGrowthConfigService.LevelsForm;
import com.tailtopia.admin.growth.service.AdminGrowthConfigService.LevelsPreview;
import com.tailtopia.admin.growth.service.AdminGrowthConfigService.RuleForm;
import com.tailtopia.admin.growth.service.AdminGrowthConfigService.SpendForm;
import com.tailtopia.admin.growth.service.GrowthConfigValidationException;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.growth.domain.GrowthRecalcJob;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
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
 * 后台「成长体系」配置页（V1.4.0 ① AB-32 · Story 4.1 · UI G1 ~ G7）：成长值规则 / 消费换算 / 等级表三个页签。
 *
 * <p>页面门 = 查看或编辑（与 {@code AdminPageCatalog} 的 {@code growth} 页 navCodes 逐字同集）；所有写端点只认编辑码，
 * 直接 POST 也 403。等级表保存走<b>服务端确认弹层</b>（预检只校验 + 算人数，不写库；照 App 版本卡 AB-22 写法）。
 * 非 htmx 提交一律 PRG + flash。
 */
@Controller
public class AdminGrowthConfigController {

    /**
     * 页面门。V1.4.0 ① Story 4.3：加 {@code growth.user_adjust}——批量发放入口在本页，P-5 预授的 OPS_MANAGER 否则进不来；
     * 只持该码时三个页签按只读渲染（模板按 {@code growth.config_edit} 判可编辑）。与目录 {@code growth} 页 navCodes 逐字同集。
     */
    static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.GROWTH_CONFIG_VIEW
            + "') or hasAuthority('" + AdminPermissions.GROWTH_CONFIG_EDIT
            + "') or hasAuthority('" + AdminPermissions.GROWTH_USER_ADJUST + "')";
    static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.GROWTH_CONFIG_EDIT
            + "')";

    static final String BASE = "/admin/growth";
    static final Set<String> TABS = Set.of("rules", "spend", "levels");


    /** 消费换算卡 422 错误码 → 字段名（模板 D 行内红边）。 */
    static final String SPEND_ERROR_FIELDS = "{"
            + "\"admin.err.growth.intMin1\":\"digitalUnit,digitalPoints,tokoUnit,tokoPoints\"}";

    private final AdminGrowthConfigService service;
    private final Messages msg;

    public AdminGrowthConfigController(AdminGrowthConfigService service, Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    @GetMapping(BASE)
    @PreAuthorize(VIEW_AUTH)
    public String page(@RequestParam(required = false) String tab, Model model) {
        model.addAttribute("active", "growth");
        model.addAttribute("tab", tab != null && TABS.contains(tab) ? tab : "rules");
        populate(model);
        return "admin/growth";
    }

    private void populate(Model model) {
        model.addAttribute("rules", service.rules());
        model.addAttribute("spend", service.spend());
        model.addAttribute("spendErrorFields", SPEND_ERROR_FIELDS);
        model.addAttribute("spendTierOptions", AdminGrowthConfigService.EDITABLE_TIERS);
        AdminGrowthConfigService.LevelsView levels = service.levels();
        model.addAttribute("levels", levels);
        // 等级表页签：最近一次重算、是否可「重新执行」、追加级可选段位（不低于当前最高级的段位）。
        GrowthRecalcJob latest = levels.latestJob().orElse(null);
        model.addAttribute("latestJob", latest);
        model.addAttribute("recalcRetryable", latest != null && latest.isRetryable(Instant.now()));
        model.addAttribute("appendTierOptions", java.util.Arrays.stream(GrowthTier.values())
                .filter(t -> levels.lastTier() == null || t.ordinal() >= levels.lastTier().ordinal()).toList());
    }

    // ── ① 规则 ───────────────────────────────────────────────────

    @GetMapping(BASE + "/rules/{code}/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String ruleDrawer(@PathVariable String code, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:" + BASE + "?tab=rules";
        }
        model.addAttribute("rule", service.editableRule(code));
        model.addAttribute("passiveGroupCap", service.rules().passiveGroupCap());
        return "admin/fragments/drawer-growth-rule :: ruleDrawer";
    }

    @PostMapping(BASE + "/rules/{code}")
    @PreAuthorize(EDIT_AUTH)
    public String updateRule(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String code,
            @RequestParam(required = false) String points, @RequestParam(required = false) String dailyCap,
            @RequestParam(required = false) String groupCap, @RequestParam(required = false) String unitAmount,
            @RequestParam(defaultValue = "false") boolean enabled,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        RuleForm form = new RuleForm(points, dailyCap, groupCap, unitAmount, enabled);
        if (hx.isHtmx()) {
            try {
                service.updateRule(code, form, admin.getAdminAccountId());
            } catch (GrowthConfigValidationException e) {
                return ruleErrors(e, service.editableRule(code).kind(), model, response);
            }
            AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_CLOSE);
            model.addAttribute("rules", service.rules());
            model.addAttribute("toast", msg.get("admin.flash.growth.ruleSaved"));
            response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#growth-rules");
            response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "outerHTML");
            return "admin/fragments/growth-rules :: saved";
        }
        try {
            service.updateRule(code, form, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.growth.ruleSaved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE + "?tab=rules";
    }

    /** 422 汇总片段（G3）：主 swap 进卡底 err 槽（「N 处填写有误」），逐字段错误以 oob 贴到 {@code #grule-err-<field>}。 */
    private String ruleErrors(GrowthConfigValidationException e, AdminGrowthConfigService.RuleKind kind, Model model,
            HttpServletResponse response) {
        // 只刷该型真有的字段槽（没有的槽 oob 找不到目标，htmx 会报 oobErrorNoTarget）。
        Map<String, List<String>> byField = new LinkedHashMap<>();
        fieldsOf(kind).forEach(f -> byField.put(f, new ArrayList<>()));
        for (GrowthConfigValidationException.FieldError fe : e.errors()) {
            byField.computeIfAbsent(fe.field(), k -> new ArrayList<>()).add(msg.get(fe.code()));
        }
        model.addAttribute("errorFieldMessages", byField);
        model.addAttribute("invalidInputs", String.join(",",
                e.errors().stream().map(GrowthConfigValidationException.FieldError::field).distinct().toList()));
        model.addAttribute("summary", msg.get("admin.v140.growth.drawer.errorSummary", e.errors().size()));
        response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        return "admin/fragments/drawer-growth-rule :: errors";
    }

    /** 抽屉里该型渲染的数字字段（与 drawer-growth-rule.html 的分型一致）。 */
    static List<String> fieldsOf(AdminGrowthConfigService.RuleKind kind) {
        return switch (kind) {
            case ACTIVE -> List.of("points", "dailyCap");
            case PASSIVE -> List.of("points", "groupCap");
            case PER_AMOUNT -> List.of("unitAmount", "points");
            case MILESTONE -> List.of("points");
            default -> List.of();
        };
    }

    // ── ② 消费换算 ───────────────────────────────────────────────

    @PostMapping(BASE + "/spend")
    @PreAuthorize(EDIT_AUTH)
    public String updateSpend(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam Map<String, String> params, HxRequest hx, Model model, HttpServletResponse response,
            RedirectAttributes flash) {
        Map<String, String> tiers = new HashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("tier.")) {
                tiers.put(k.substring("tier.".length()), v);
            }
        });
        SpendForm form = new SpendForm(params.get("digitalUnit"), params.get("digitalPoints"),
                params.get("tokoUnit"), params.get("tokoPoints"), tiers);
        if (hx.isHtmx()) {
            service.updateSpend(form, admin.getAdminAccountId()); // 422 由 AdminBusinessExceptionAdvice 回卡底 err 槽
            return savedCard("cfg-growth-spend", "growth-spend-card", "admin.flash.growth.spendSaved", model, response);
        }
        try {
            service.updateSpend(form, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.growth.spendSaved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE + "?tab=spend";
    }

    // ── ③ 等级表 ─────────────────────────────────────────────────

    /** 预检（G6）：只校验 + 按候选新表算人数，不写库；通过回服务端确认弹层到 {@code #confirm-host}。 */
    @PostMapping(BASE + "/levels/confirm")
    @PreAuthorize(EDIT_AUTH)
    public String confirmLevels(@RequestParam Map<String, String> params, HxRequest hx, Model model,
            HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:" + BASE + "?tab=levels";
        }
        LevelsForm form = levelsForm(params);
        LevelsPreview preview = service.previewLevels(form);
        model.addAttribute("cm", levelsConfirmModal(preview, form));
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#confirm-host");
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
        return "admin/fragments/confirm-modal :: modal";
    }

    @PostMapping(BASE + "/levels")
    @PreAuthorize(EDIT_AUTH)
    public String saveLevels(@AuthenticationPrincipal AdminUserDetails admin, @RequestParam Map<String, String> params,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        LevelsForm form = levelsForm(params);
        if (hx.isHtmx()) {
            service.saveLevels(form, admin.getAdminAccountId()); // 422 回弹层内 #confirm-modal-err
            // 在 swap 之后再收弹层：先收的话发请求的表单已不在 DOM 里
            response.setHeader("HX-Trigger-After-Swap", AdminHxEvents.CONFIRM_CLOSE);
            return savedCard("cfg-growth-levels-wrap", "growth-levels-card", "admin.flash.growth.levelsSaved", model,
                    response);
        }
        try {
            service.saveLevels(form, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.growth.levelsSaved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE + "?tab=levels";
    }

    @PostMapping(BASE + "/levels/recalc")
    @PreAuthorize(EDIT_AUTH)
    public String retryRecalc(@AuthenticationPrincipal AdminUserDetails admin, HxRequest hx, Model model,
            HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            service.retryRecalc(admin.getAdminAccountId());
            return savedCard("cfg-growth-levels-wrap", "growth-levels-card", "admin.flash.growth.recalcRetried", model,
                    response);
        }
        try {
            service.retryRecalc(admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.growth.recalcRetried"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE + "?tab=levels";
    }

    /** 表单字段：{@code lv.<n>} = 门槛、{@code appendThreshold} / {@code appendTier} = 追加一级。 */
    static LevelsForm levelsForm(Map<String, String> params) {
        Map<Integer, String> thresholds = new HashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("lv.")) {
                try {
                    thresholds.put(Integer.parseInt(k.substring(3)), plainDigits(v));
                } catch (NumberFormatException ignored) {
                    // 非法键忽略（不会出现在正常页面）
                }
            }
        });
        return new LevelsForm(thresholds, plainDigits(params.get("appendThreshold")), params.get("appendTier"));
    }

    /** 规范的千分位写法（UI G5 门槛框按 {@code 4,500} 显示）。 */
    private static final java.util.regex.Pattern GROUPED = java.util.regex.Pattern.compile("\\d{1,3}(,\\d{3})+");

    /**
     * 门槛框按千分位显示（UI G5）→ 提交回来先还原成纯数字再交给服务层校验。
     * 只认规范分组（{@code 12,400}）；{@code 1,2,3} 这类不规范写法原样返回，照旧走「须为正整数」的 422，不猜用户意图。
     */
    static String plainDigits(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.strip();
        return GROUPED.matcher(t).matches() ? t.replace(",", "") : raw;
    }

    /** G6 弹层：逐条改动 + 升 / 降级人数（服务端按候选新表算）+ 红色提醒；确认后带同一份表单值 POST 保存端点。 */
    private AdminConfirmModal levelsConfirmModal(LevelsPreview preview, LevelsForm form) {
        List<AdminConfirmModal.DiffLine> lines = new ArrayList<>();
        for (AdminGrowthConfigService.LevelChange c : preview.plan().changes()) {
            lines.add(new AdminConfirmModal.DiffLine(msg.get("admin.v140.growth.levels.confirm.threshold", c.level()),
                    String.valueOf(c.oldThreshold()), String.valueOf(c.newThreshold())));
        }
        AdminGrowthConfigService.LevelAppend a = preview.plan().append();
        if (a != null) {
            lines.add(new AdminConfirmModal.DiffLine(msg.get("admin.v140.growth.levels.confirm.append", a.level()), "",
                    a.threshold() + " · " + msg.get("admin.v140.growth.tier." + a.tier().name())));
        }
        List<AdminConfirmModal.Fact> facts = List.of(
                new AdminConfirmModal.Fact(msg.get("admin.v140.growth.levels.confirm.up"),
                        msg.get("admin.v140.growth.levels.confirm.people", preview.counts().upgraded())),
                new AdminConfirmModal.Fact(msg.get("admin.v140.growth.levels.confirm.down"),
                        msg.get("admin.v140.growth.levels.confirm.people", preview.counts().downgraded())));
        Map<String, String> hidden = new LinkedHashMap<>();
        form.thresholds().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> hidden.put("lv." + e.getKey(), e.getValue()));
        if (form.appendThreshold() != null && !form.appendThreshold().isBlank()) {
            hidden.put("appendThreshold", form.appendThreshold());
            hidden.put("appendTier", form.appendTier() == null ? "" : form.appendTier());
        }
        return new AdminConfirmModal(msg.get("admin.v140.growth.levels.confirm.title"),
                msg.get("admin.v140.growth.levels.confirm.danger"), lines,
                null, null, null, null, BASE + "/levels", hidden,
                msg.get("admin.v140.growth.levels.confirm.btn"), false, false, facts);
    }

    private String savedCard(String cardId, String fragment, String toastKey, Model model,
            HttpServletResponse response) {
        populate(model);
        model.addAttribute("toast", msg.get(toastKey));
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#" + cardId);
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "outerHTML");
        return "admin/fragments/" + fragment + " :: saved";
    }
}
