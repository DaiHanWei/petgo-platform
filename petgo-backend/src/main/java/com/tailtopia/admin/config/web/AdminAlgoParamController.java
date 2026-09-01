package com.tailtopia.admin.config.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.config.dto.FeedRankForm;
import com.tailtopia.admin.config.service.AdminConfigService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.shared.error.AppException;
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
 * 但埋在审计后台里没人会去翻。本页把最近 20 条直接展示出来 ——
 * 没有 A/B 时，「谁在什么时候把哪个值从多少改成了多少」是唯一能与指标漂移对上的锚点。
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

    private final PlatformConfigService read;
    private final AdminConfigService write;
    private final ConfigChangeLogRepository changeLogs;

    public AdminAlgoParamController(PlatformConfigService read, AdminConfigService write,
            ConfigChangeLogRepository changeLogs) {
        this.read = read;
        this.write = write;
        this.changeLogs = changeLogs;
    }

    @GetMapping("/admin/algo-params")
    @PreAuthorize(VIEW)
    public String page(Model model) {
        model.addAttribute("active", "algo-params");
        model.addAttribute("feedRank", read.feedRank());
        model.addAttribute("changeLogs",
                changeLogs.findTop20ByConfigTypeOrderByChangedAtDesc(ConfigChangeLog.ConfigType.FEED_RANK));
        return "admin/algo-params";
    }

    /**
     * 保存。校验（配比自洽、限流系数开区间）全在 {@link AdminConfigService#updateFeedRank} 里，
     * 与 diff 审计同一处 —— 本类只做参数绑定与回显。
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
            @RequestParam int speciesGeneralQuota, RedirectAttributes flash) {
        try {
            write.updateFeedRank(new FeedRankForm(freshnessWeight, interactionWeight, commentWeight,
                    exposureDecay, shuffleStrength, throttleFactor, seenWindowDays, windowSize, attrFunQuota,
                    attrEduQuota, attrLifeQuota, speciesMainQuota, speciesOtherQuota,
                    speciesGeneralQuota), admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "算法参数已更新（逐字段留痕，见下方变更记录）");
        } catch (AppException e) {
            // 校验失败走 flash 回本页，不吃整页 500（沿用运营配置页既有口径）。
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/algo-params";
    }
}
