package com.tailtopia.admin.config.web;

import com.tailtopia.admin.config.dto.FeedRankForm;
import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.config.dto.KtpPricingForm;
import com.tailtopia.admin.config.dto.PawCoinForm;
import com.tailtopia.admin.config.dto.PricingForm;
import com.tailtopia.admin.config.service.AdminConfigService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
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
 * 后台运营配置（Story 9.2，AB-8A/8F/6A/6B）。Thymeleaf admin slice，{@code /admin/config/**}，redirect+flash。
 * 门控：查看 {@code config.view} / 修改 {@code config.edit}（SUPER_ADMIN 隐式全权）。校验/变更日志/审计在
 * {@link AdminConfigService}。改值只影响后续（历史落快照）。
 */
@Controller
public class AdminConfigController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('config.view')";
    private static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('config.edit')";

    /**
     * 分享奖励的两个独立权限（Story 18.3 · AC5）。
     *
     * <p>🛡 从 {@link AdminPermissions} 常量拼出来，模板里的 {@code sec:authorize} 用的是
     * 同一个码 —— 「侧栏门与控制器注解逐字一致」那条要求就不靠人去对照两段字符串。
     */
    static final String SHARE_REWARD_VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.CONFIG_SHARE_REWARD_VIEW + "')";
    static final String SHARE_REWARD_EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.CONFIG_SHARE_REWARD_EDIT + "')";

    /**
     * 页面准入（Story 18.3 · AC5 修订）：持任意 config.* 读权限即可进入本页。
     *
     * <p>🔴 只持 {@code config.share_reward_*} 的应急操作员必须能打开本页 ——
     * 总开关的意义是「发现被刷要能立刻关掉」，页面门槛若卡 {@code config.view}，
     * 杀开关经 UI 根本走不到。🛡 只放宽<b>准入</b>：页内定价 / PawCoin / 档位各块仍按
     * {@code config.view} / {@code config.edit} 门控，分享奖励块按 share_reward 两码门控
     * （见 {@code templates/admin/config.html}），各 POST 端点的写权限一律未放宽。
     */
    static final String PAGE_AUTH = VIEW_AUTH
            + " or hasAuthority('" + AdminPermissions.CONFIG_SHARE_REWARD_VIEW + "')"
            + " or hasAuthority('" + AdminPermissions.CONFIG_SHARE_REWARD_EDIT + "')";

    private final PlatformConfigService read;
    private final com.tailtopia.share.repository.ShareRewardQuotaStatsRepository shareStats;
    private final AdminConfigService write;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminConfigController(PlatformConfigService read, AdminConfigService write,
            Messages msg,
            com.tailtopia.share.repository.ShareRewardQuotaStatsRepository shareStats) {
        this.read = read;
        this.shareStats = shareStats;
        this.write = write;
        this.msg = msg;
    }

    @GetMapping("/admin/config")
    @PreAuthorize(PAGE_AUTH)
    public String view(Model model) {
        model.addAttribute("active", "config");
        model.addAttribute("pricing", read.pricing());
        model.addAttribute("pawcoin", read.pawcoin());
        // V1.3.0 Story 6.2（AB-22A / D-25）：主表只列启用中；已停用折叠区按需 htmx 载入（N=0 不渲染链接）
        model.addAttribute("tiers", read.enabledTiers());
        model.addAttribute("disabledCount", read.disabledTierCount());
        // V1.1.6 Story 16.4：推荐算法参数（挂既有配置页，🛡 不新建后台模块）
        // Story 18.3 · AC2/AC3：白嫖倍数与当月消耗必须**同屏**——
        // 「月度上限 30」和「HD 解锁 60」分开看都合理，放一起才看得出「两个月白嫖一次」。
        model.addAttribute("shareRewardOverview", shareRewardOverview());
        return "admin/config";
    }

    /**
     * 分享奖励四项（Story 18.3 · AB-3M）。
     *
     * <p>🛡 独立端点 + 独立权限（AC5）：总开关的意义是「发现被刷要能立刻关掉」，
     * 塞进 {@code config.edit} 就意味着想关它的人必须同时握有改兽医定价与分成的权限。
     * ⚠️ checkbox 未勾选时浏览器**不提交该参数** ⇒ {@code shareRewardEnabled}
     * 必须给 {@code defaultValue="false"}，否则「取消勾选」会 400 而不是关掉开关。
     */
    @PostMapping("/admin/config/share-reward")
    @PreAuthorize(SHARE_REWARD_EDIT_AUTH)
    public String updateShareReward(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(defaultValue = "false") boolean shareRewardEnabled,
            @RequestParam(defaultValue = "0") long shareRewardMonthlyCap,
            @RequestParam(defaultValue = "0") long idCardShareReward,
            @RequestParam(defaultValue = "0") int idCardShareDailyCap,
            RedirectAttributes flash) {
        try {
            write.updateShareReward(new com.tailtopia.admin.config.dto.ShareRewardForm(
                    shareRewardEnabled, shareRewardMonthlyCap, idCardShareReward,
                    idCardShareDailyCap), admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.config.shareRewardSaved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/config";
    }

    /** 分享奖励的同屏对照数据（HD 价 / 白嫖倍数 / 当月消耗）。 */
    private com.tailtopia.admin.config.dto.ShareRewardOverview shareRewardOverview() {
        long hdPrice = read.pricing().getIdHdDownloadPrice();
        long cap = read.pawcoin().getShareRewardMonthlyCap();
        String period = com.tailtopia.share.service.ShareRewardService
                .periodOf(java.time.Instant.now());
        return new com.tailtopia.admin.config.dto.ShareRewardOverview(
                hdPrice, cap,
                com.tailtopia.admin.config.dto.ShareRewardOverview
                        .monthsPerHdUnlock(hdPrice, cap),
                shareStats.sumGranted(period),
                cap > 0 ? shareStats.countAtCap(period, cap) : 0,
                period);
    }

    /** 定价卡四项（V1.3.0 Story 6.1 起不再接收 {@code idHdDownloadPrice}——KTP 卡高清价归 {@link #updateKtpPricing}）。 */
    @PostMapping("/admin/config/pricing")
    @PreAuthorize(EDIT_AUTH)
    public String updatePricing(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long vetConsultPrice, @RequestParam int vetShareRate,
            @RequestParam long aiUnlockPrice,
            @RequestParam int monthlyFreeQuota, RedirectAttributes flash) {
        try {
            write.updatePricing(new PricingForm(vetConsultPrice, vetShareRate, aiUnlockPrice,
                    monthlyFreeQuota), admin.getAdminAccountId());
            // bug 20260721-346：定价保存成功提示改用 toast（短暂自动消失），区别于常驻 notice 横幅。
            flash.addFlashAttribute("toast", msg.get("admin.flash.config.pricingSaved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/config";
    }

    /**
     * 「KTP 模块高清图解锁定价」三行（V1.3.0 Story 6.1 · AB-18A）：KTP 卡高清下载 / 护照·护照内页 / 护照·登机牌。
     * 与定价卡同码（{@code config.edit} 改、{@code config.view} 看，不新增权限）；PRG + toast；校验失败 flash error 回显
     * （htmx 422 fragment 随 Story 6.3 套模板 D 一起接）。
     */
    @PostMapping("/admin/config/ktp-pricing")
    @PreAuthorize(EDIT_AUTH)
    public String updateKtpPricing(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long idHdDownloadPrice, @RequestParam long passportPagePrice,
            @RequestParam long passportBoardingPrice, RedirectAttributes flash) {
        try {
            write.updateKtpPricing(new KtpPricingForm(idHdDownloadPrice, passportPagePrice, passportBoardingPrice),
                    admin.getAdminAccountId());
            flash.addFlashAttribute("toast", msg.get("admin.flash.config.ktpPricingSaved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/config";
    }

    @PostMapping("/admin/config/pawcoin")
    @PreAuthorize(EDIT_AUTH)
    public String updatePawCoin(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam int premiumRate,
            @RequestParam(defaultValue = "0") long premiumFixed,
            @RequestParam(defaultValue = "false") boolean topupPaused, RedirectAttributes flash) {
        try {
            write.updatePawCoin(new PawCoinForm(premiumRate, premiumFixed, topupPaused),
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.config.pawcoinSaved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/config";
    }

    /**
     * 档位启停。V1.3.0 Story 6.2：htmx 请求（已停用折叠表 / 主表内的按钮）成功回两表 fragment（主表 + 已停用表 oob + toast），
     * 失败（达上限 / 保底 ≥1）由 {@code AdminBusinessExceptionAdvice} 出 422 行内 err；非 htmx 维持 PRG。
     */
    @PostMapping("/admin/config/tiers/{id}/enabled")
    @PreAuthorize(EDIT_AUTH)
    public String setTierEnabled(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, @RequestParam boolean enabled, @RequestParam(value = "expanded", defaultValue = "0") String expanded,
            HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            write.setTierEnabled(id, enabled, admin.getAdminAccountId());
            populateTiers(model);
            model.addAttribute("oob", true); // 两表以 hx-swap-oob 按 id 原位替换，主目标只清空行内 err
            model.addAttribute("expanded", "1".equals(expanded)); // 折叠区已展开才替换已停用表，保持「按需展开」语义
            model.addAttribute("toast", msg.get("admin.flash.config.tiersSaved"));
            return "admin/fragments/config-tiers-disabled :: refresh";
        }
        try {
            write.setTierEnabled(id, enabled, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.config.tiersSaved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/config";
    }

    /** 「查看已停用（N）」折叠表 fragment（V1.3.0 Story 6.2 AC2；查看即 {@code config.view}）。 */
    @GetMapping("/admin/config/tiers/disabled")
    @PreAuthorize(VIEW_AUTH)
    public String disabledTiers(HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/config";
        }
        populateTiers(model);
        return "admin/fragments/config-tiers-disabled :: table";
    }

    /** 新建档位（V1.3.0 Story 6.2 AC3）：金额 IDR 正整数 → PRG + toast；重复 / 达上限 / ≤0 → flash error 回显。 */
    @PostMapping("/admin/config/tiers")
    @PreAuthorize(EDIT_AUTH)
    public String createTier(@AuthenticationPrincipal AdminUserDetails admin, @RequestParam long amountIdr, RedirectAttributes flash) {
        try {
            write.createTier(amountIdr, admin.getAdminAccountId());
            flash.addFlashAttribute("toast", msg.get("admin.flash.config.tierCreated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/config";
    }

    private void populateTiers(Model model) {
        model.addAttribute("tiers", read.enabledTiers());
        model.addAttribute("disabledTiers", read.disabledTiers());
        model.addAttribute("disabledCount", read.disabledTierCount());
    }
}
