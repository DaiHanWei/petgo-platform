package com.tailtopia.profile.web;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.MilestoneShare;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.service.MilestoneShareService;
import com.tailtopia.profile.service.ProfileService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 里程碑庆祝对外 H5（P-35 分享链接）。Thymeleaf 服务端直出 {@code GET /m/{shareToken}}，**公开无需鉴权**
 * （SecurityConfig 同 {@code /p/**} 放行）。复刻 App 内 P-35 解锁庆祝页（深墨底 + 渐变徽章 + 级别 chip +
 * 标题/正文 + 宠物·日期 + 彩纸），**唯一差异**：底部「分享 / 查看全部」换成单个「下载 App」CTA（平台分流同名片）。
 *
 * <p>失效（token 不存在 / 账号注销）→ 统一复用名片失效页 {@code card_gone} + 404 + noindex（防枚举）。
 * 展示文案取分享落库时客户端已本地化的 {@code title/body}（杜绝后端中文泄漏）；级别 chip / 标头 / 日期月份名
 * 按分享 {@code locale} 出。无 OG 预览图（不外露宠物图，隐私边界）。
 */
@Controller
public class MilestoneSharePageController {

    private final MilestoneShareService shareService;
    private final ProfileService profileService;
    private final AccountQueryService accountQueryService;
    private final String downloadUrl;
    private final String iosUrl;
    private final String androidUrl;

    public MilestoneSharePageController(MilestoneShareService shareService,
            ProfileService profileService, AccountQueryService accountQueryService,
            @Value("${petgo.card.app-download-url:https://petgo.example/download}") String downloadUrl,
            @Value("${petgo.card.ios-url:https://apps.apple.com/app/petgo}") String iosUrl,
            @Value("${petgo.card.android-url:https://play.google.com/store/apps/details?id=com.tailtopia.app}")
                    String androidUrl) {
        this.shareService = shareService;
        this.profileService = profileService;
        this.accountQueryService = accountQueryService;
        this.downloadUrl = downloadUrl;
        this.iosUrl = iosUrl;
        this.androidUrl = androidUrl;
    }

    @GetMapping("/m/{shareToken}")
    public String share(@PathVariable String shareToken, Model model, HttpServletResponse response) {
        Optional<MilestoneShare> opt = shareService.findByToken(shareToken);
        if (opt.isEmpty()) {
            return gone(model, response);
        }
        MilestoneShare share = opt.get();
        // 账号注销 / 档案已删 → 与不存在同一失效页（防枚举）。
        Optional<PetProfile> profile = profileService.findById(share.getPetProfileId());
        if (profile.isEmpty() || !accountQueryService.isActive(profile.get().getOwnerId())) {
            return gone(model, response);
        }

        boolean isId = "id".equals(share.getLocale());
        model.addAttribute("title", share.getTitle());
        model.addAttribute("body", share.getBody());
        model.addAttribute("hasBody", share.getBody() != null && !share.getBody().isBlank());
        model.addAttribute("petName", share.getPetName());
        model.addAttribute("speciesEmoji", speciesEmoji(share.getCode()));
        model.addAttribute("dateLabel", dateLabel(share, isId));
        model.addAttribute("unlockedHeader", isId ? "TONGGAK TERBUKA!" : "MILESTONE UNLOCKED!");
        model.addAttribute("levelLabel", levelLabel(share.getLevel()));
        model.addAttribute("levelColor", levelColor(share.getLevel()));
        model.addAttribute("downloadCta", isId ? "Buat arsip untuk hewanku" : "Create your pet's archive");
        model.addAttribute("ogTitle", share.getTitle());

        // 「已解锁合集」快照（与 P-35 KOLEKSI 区对齐）：级别串 + 本地化标头 + 总数；圆点/「+N」由模板 JS 复刻。
        String levels = share.getCollectionLevels() == null ? "" : share.getCollectionLevels();
        model.addAttribute("hasCollection", !levels.isBlank());
        model.addAttribute("collectionLevels", levels);
        model.addAttribute("collectionHeading", collectionHeading(share.getPetName(), levels.length(), isId));

        // 下载 CTA：直接跳应用商店下载（iOS App Store / Android Google Play；桌面落下载页）——不再尝试深链。
        model.addAttribute("downloadUrl", downloadUrl);
        model.addAttribute("iosUrl", iosUrl);
        model.addAttribute("androidUrl", androidUrl);
        return "milestone_share";
    }

    // 物种 emoji（按 code 前缀：C=猫 / D=狗 / 其余=通用），与 App milestone_celebration 一致。
    private static String speciesEmoji(String code) {
        if (code == null || code.isEmpty()) {
            return "🐾";
        }
        return switch (code.charAt(0)) {
            case 'C' -> "🐱";
            case 'D' -> "🐶";
            default -> "🐾";
        };
    }

    // 级别 chip 文案（id/en 同值，与 ARB milestoneLevelChip* 一致）。
    private static String levelLabel(String level) {
        return switch (level) {
            case "L" -> "L · LEGENDA";
            case "M" -> "M · MAJOR";
            default -> "S · SMALL";
        };
    }

    // 合集标头（与 App milestoneCelebrateCollection 一致：整串大写 + 「 · 总数」）。
    private static String collectionHeading(String petName, int count, boolean isId) {
        String base = isId ? ("Koleksi " + petName) : (petName + "'s collection");
        return base.toUpperCase() + " · " + count;
    }

    // 级别配色（与 App 一致：L 金 / M 紫 / S 绿）。
    private static String levelColor(String level) {
        return switch (level) {
            case "L" -> "#F6A609";
            case "M" -> "#845EC9";
            default -> "#1F9E6A";
        };
    }

    private static final String[] MONTHS_ID = {
        "Januari", "Februari", "Maret", "April", "Mei", "Juni",
        "Juli", "Agustus", "September", "Oktober", "November", "Desember"
    };
    private static final String[] MONTHS_EN = {
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    };

    /** 「🐱 Momo · 15 Juni 2026」中的「宠物名 · 日期」；无完成时间则仅宠物名。与 App 手写月份名一致（UTC 取日）。 */
    private static String dateLabel(MilestoneShare share, boolean isId) {
        if (share.getCompletedAt() == null) {
            return share.getPetName();
        }
        LocalDate d = share.getCompletedAt().atZone(ZoneOffset.UTC).toLocalDate();
        String[] months = isId ? MONTHS_ID : MONTHS_EN;
        String date = d.getDayOfMonth() + " " + months[d.getMonthValue() - 1] + " " + d.getYear();
        return share.getPetName() + " · " + date;
    }

    private String gone(Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        // 复用名片失效页（card_gone）：统一「不存在 / 删除 / 注销」三态响应，CTA 落下载。
        model.addAttribute("downloadUrl", downloadUrl);
        return "card_gone";
    }
}
