package com.tailtopia.admin.config.web;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.config.dto.FeedRankForm;
import com.tailtopia.admin.config.service.AdminConfigService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 「算法参数」独立页（2026-08-26 产品决定）。原属 Story 16.4 的运营配置页一个区块。
 *
 * <h2>🔴 为什么从「运营配置」搬出来单独成页</h2>
 * 调研（Google Cloud Retail 的 serving controls 是产品化后的样本）显示，行业把这件事分两层：
 * <ul>
 *   <li><b>业务规则层</b>——加权 / 降权 / 置顶 / 过滤，作用在模型出结果<b>之后</b>。
 *       这一层是运营后台的标配，我们的顶置、内容打标、限流处置都属于它。</li>
 *   <li><b>模型参数层</b>——打分公式的权重与分位数。产品化的系统<b>明确不把它开放给运营</b>
 *       （原文：controls "not by adjusting the underlying machine learning model's parameters or weights"）。</li>
 * </ul>
 * 两层混在同一页里，视觉上就是在暗示一种它并不具备的可控性。
 *
 * <h2>🔴 为什么不给运营</h2>
 * 大厂敢开放模型参数是因为背后有 <b>A/B 实验平台</b>兜底 —— 改了能立刻看到两组数据的差异。
 * 本平台<b>没有灰度分流基建</b>（全量上线、不加开关），
 * 也就是说这一页改完<b>没有任何机制能判定对错</b>，只能看整体指标漂移。
 * 所以留给产品做校准，运营不开放。
 *
 * <h2>所有操作留日志，并摆在同一屏</h2>
 * 逐字段的变更早就写进 {@code config_change_logs}（Story 9.2 的 diff 审计），
 * 但埋在审计后台里没人会去翻。V1.3.0 Story 6.4（D-10）起页尾常驻表改为页头「变更记录」按钮开抽屉
 * （{@link #changesDrawer}：按参数 / 时间段筛选、每页 20，默认最近 20 条）——历史可查而不占版面。
 * 页面套模板 D（保存钮三态 + 确认复述改前改后 + 422 行内），{@code POST /admin/algo-params} 与全部校验不变。
 */
@Controller
public class AdminAlgoParamController {

    /**
     * 🛡 独立权限码，<b>与 config.view / config.edit 解耦</b>。
     *
     * <p>⚠️ 侧栏 {@code sec:authorize} 引用的是同一个 {@link AdminPermissions} 常量，
     * 「侧栏门与控制器注解逐字一致」那条要求因此不靠人去对照两段字符串。
     */
    static final String VIEW = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.CONFIG_ALGO_PARAM_VIEW + "')";
    static final String EDIT = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.CONFIG_ALGO_PARAM_EDIT + "')";

    /** 抽屉每页条数（AC3）。 */
    static final int PAGE_SIZE = 20;

    /** 变更记录里的参数列名 → 表单字段名 / 标签 key（14 项，与 {@link FeedRankForm} 一一对应；PRD 写 11 项以代码为准）。 */
    public record AlgoField(String column, String name, String labelKey) {
    }

    static final List<AlgoField> FIELDS = List.of(
            new AlgoField("freshness_weight", "freshnessWeight", "admin.algo.freshnessWeight"),
            new AlgoField("interaction_weight", "interactionWeight", "admin.algo.interactionWeight"),
            new AlgoField("comment_weight", "commentWeight", "admin.algo.commentWeight"),
            new AlgoField("exposure_decay", "exposureDecay", "admin.algo.exposureDecay"),
            new AlgoField("shuffle_strength", "shuffleStrength", "admin.algo.shuffleStrength"),
            new AlgoField("throttle_factor", "throttleFactor", "admin.algo.throttleFactor"),
            new AlgoField("seen_window_days", "seenWindowDays", "admin.algo.seenWindowDays"),
            new AlgoField("window_size", "windowSize", "admin.algo.windowSize"),
            new AlgoField("attr_fun_quota", "attrFunQuota", "admin.algo.attrFunQuota"),
            new AlgoField("attr_edu_quota", "attrEduQuota", "admin.algo.attrEduQuota"),
            new AlgoField("attr_life_quota", "attrLifeQuota", "admin.algo.attrLifeQuota"),
            new AlgoField("species_main_quota", "speciesMainQuota", "admin.algo.speciesMainQuota"),
            new AlgoField("species_other_quota", "speciesOtherQuota", "admin.algo.speciesOtherQuota"),
            new AlgoField("species_general_quota", "speciesGeneralQuota", "admin.algo.speciesGeneralQuota"));

    /** 422 错误码 → 卡内字段名（模板 D 行内红边，同 6.3 {@code AdminConfigController.ERROR_FIELDS}）；校验本身一行不动。 */
    static final String ERROR_FIELDS = "{"
            + "\"admin.err.config.weightNegative\":\"freshnessWeight,interactionWeight\","
            + "\"admin.err.config.weightsAllZero\":\"freshnessWeight,interactionWeight\","
            + "\"admin.err.config.commentWeightNegative\":\"commentWeight\","
            + "\"admin.err.config.exposureDecayRange\":\"exposureDecay\","
            + "\"admin.err.config.shuffleStrengthRange\":\"shuffleStrength\","
            + "\"admin.err.config.throttleFactorRange\":\"throttleFactor\","
            + "\"admin.err.config.seenWindowDaysMin\":\"seenWindowDays\","
            + "\"admin.err.config.attrWindowTooSmall\":\"windowSize\","
            + "\"admin.err.config.attrQuotaNegative\":\"attrFunQuota,attrEduQuota,attrLifeQuota\","
            + "\"admin.err.config.attrQuotaSumMismatch\":\"attrFunQuota,attrEduQuota,attrLifeQuota,windowSize\","
            + "\"admin.err.config.attrQuotaTooConcentrated\":\"attrFunQuota,attrEduQuota,attrLifeQuota\","
            + "\"admin.err.config.speciesQuotaNegative\":\"speciesMainQuota,speciesOtherQuota,speciesGeneralQuota\","
            + "\"admin.err.config.speciesQuotaSumMismatch\":\"speciesMainQuota,speciesOtherQuota,speciesGeneralQuota,windowSize\"}";

    private final PlatformConfigService read;
    private final AdminConfigService write;
    private final ConfigChangeLogRepository changeLogs;
    private final AdminAccountRepository adminAccounts;
    private final AdminTime adminTime;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminAlgoParamController(PlatformConfigService read, AdminConfigService write,
            ConfigChangeLogRepository changeLogs, AdminAccountRepository adminAccounts, AdminTime adminTime, Messages msg) {
        this.read = read;
        this.write = write;
        this.changeLogs = changeLogs;
        this.adminAccounts = adminAccounts;
        this.adminTime = adminTime;
        this.msg = msg;
    }

    @GetMapping("/admin/algo-params")
    @PreAuthorize(VIEW)
    public String page(Model model) {
        model.addAttribute("active", "algo-params");
        populateCard(model);
        return "admin/algo-params";
    }

    private void populateCard(Model model) {
        model.addAttribute("feedRank", read.feedRank());
        model.addAttribute("errorFields", ERROR_FIELDS);
    }

    /**
     * 保存。校验（配比自洽、限流系数开区间）全在 {@link AdminConfigService#updateFeedRank} 里，
     * 与 diff 审计同一处 —— 本类只做参数绑定与回显。
     * V1.3.0 Story 6.4：htmx 提交成功回卡 fragment（HX-Retarget / HX-Reswap 原位替换 + toast），失败由
     * {@code AdminBusinessExceptionAdvice} 出 422 行内 err；非 htmx 维持 PRG。
     */
    @PostMapping("/admin/algo-params")
    @PreAuthorize(EDIT)
    public String save(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam double freshnessWeight, @RequestParam double interactionWeight,
            @RequestParam double commentWeight, @RequestParam double exposureDecay,
            @RequestParam double shuffleStrength,
            @RequestParam double throttleFactor, @RequestParam int seenWindowDays,
            @RequestParam int windowSize, @RequestParam int attrFunQuota,
            @RequestParam int attrEduQuota, @RequestParam int attrLifeQuota,
            @RequestParam int speciesMainQuota, @RequestParam int speciesOtherQuota,
            @RequestParam int speciesGeneralQuota, HxRequest hx, Model model, HttpServletResponse response,
            RedirectAttributes flash) {
        FeedRankForm form = new FeedRankForm(freshnessWeight, interactionWeight, commentWeight,
                exposureDecay, shuffleStrength, throttleFactor, seenWindowDays, windowSize, attrFunQuota,
                attrEduQuota, attrLifeQuota, speciesMainQuota, speciesOtherQuota, speciesGeneralQuota);
        if (hx.isHtmx()) {
            write.updateFeedRank(form, admin.getAdminAccountId());
            populateCard(model);
            model.addAttribute("toast", msg.get("admin.flash.algo.paramsSaved"));
            response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#cfg-algo");
            response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "outerHTML");
            return "admin/algo-params :: saved";
        }
        try {
            write.updateFeedRank(form, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.algo.paramsSaved"));
        } catch (AppException e) {
            // 校验失败走 flash 回本页，不吃整页 500（沿用运营配置页既有口径）。
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/algo-params";
    }

    /**
     * 变更记录抽屉（V1.3.0 Story 6.4 AC3）：{@code config_change_logs} 里 FEED_RANK 类型按参数 / 时间段（WIB 自然日，半开区间
     * [from 00:00, to+1 00:00)）筛选、{@code changed_at} 倒序分页每页 20；默认最近 20 条。操作人 id → 后台账号显示名。
     * 查看即 {@link #VIEW}；非 htmx 访问回整页。
     */
    @GetMapping("/admin/algo-params/changes/drawer")
    @PreAuthorize(VIEW)
    public String changesDrawer(@RequestParam(value = "field", required = false) String field,
            @RequestParam(value = "from", required = false) String fromRaw,
            @RequestParam(value = "to", required = false) String toRaw,
            @RequestParam(value = "page", required = false) String pageRaw, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/algo-params";
        }
        // 手改 URL 的非法日期 / 页码宽松处理成「不筛」（复审 #4）：否则 400 ProblemDetail JSON 会被 htmx 直接 swap 进抽屉
        LocalDate from = parseDate(fromRaw);
        LocalDate to = parseDate(toRaw);
        int page = parsePage(pageRaw);
        String f = field == null || field.isBlank() || FIELDS.stream().noneMatch(x -> x.column().equals(field)) ? null : field;
        Instant fromAt = from == null ? null : from.atStartOfDay(adminTime.zone()).toInstant();
        Instant toAt = to == null ? null : to.plusDays(1).atStartOfDay(adminTime.zone()).toInstant();
        Page<ConfigChangeLog> changes = changeLogs.search(ConfigChangeLog.ConfigType.FEED_RANK, f, fromAt, toAt,
                PageRequest.of(page, PAGE_SIZE, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "changedAt", "id")));
        Map<Long, String> actorNames = changes.getContent().isEmpty() ? Map.of()
                : adminAccounts.findAllById(changes.getContent().stream().map(ConfigChangeLog::getChangedBy).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(AdminAccount::getId, AdminAccount::getDisplayName, (a, b) -> a));
        model.addAttribute("changes", changes);
        model.addAttribute("fields", FIELDS);
        model.addAttribute("fieldLabels", FIELDS.stream().collect(Collectors.toMap(AlgoField::column, AlgoField::labelKey, (a, b) -> a, LinkedHashMap::new)));
        model.addAttribute("actorNames", actorNames);
        model.addAttribute("field", f);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        return "admin/fragments/drawer-algo-changes :: drawer";
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static int parsePage(String raw) {
        try {
            return raw == null || raw.isBlank() ? 0 : Math.max(Integer.parseInt(raw.strip()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
