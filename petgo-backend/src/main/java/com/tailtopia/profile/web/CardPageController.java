package com.tailtopia.profile.web;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.ArchiveStatsResponse;
import com.tailtopia.profile.service.TimelineService;
import com.tailtopia.shared.media.AliyunOssClient;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 宠物名片对外 H5（Story 2.6）。Thymeleaf 服务端直出 {@code GET /p/{cardToken}}，**公开无需鉴权**。
 *
 * <p>🔄 F2 6 区块成长故事页：① Hero（头像 + 名字 +「和 [昵称] 在一起 X 天」陪伴天数）；② 里程碑徽章条
 * （零态降级，AC5）；③ 故事数字（快乐时刻 / 问诊 / 里程碑完成，经 service 计数，不泄健康内容）；
 * ④ 最近里程碑动态（零态降级）；⑤ 快乐时刻照片流（最近 5 条 type=GROWTH_MOMENT，**按 event_date 倒序**，
 * AC7）；⑥ 双 CTA（平台分流 iOS/Android）。**不含日常/科普/健康事件详情**（隐私边界）。
 *
 * <p>失效（token 不存在 / 账号注销）→ 统一 404 友好失效页 + noindex（防枚举）。注：pet_profiles 无
 * 软删列，V1 单宠物档案删除仅经账号注销级联（7.3），故 AC4/AC6「档案已删」收敛到账号注销/ token 缺失路径。
 * 对外图均经 E4 服务端去 EXIF 分发。
 */
@Controller
public class CardPageController {

    private static final int MAX_MOMENTS = 5;

    private final ProfileServiceFacade profiles;
    private final ContentService contentService;
    private final AccountQueryService accountQueryService;
    private final TimelineService timelineService;
    private final String downloadUrl;
    private final String iosUrl;
    private final String androidUrl;
    private final String publicBaseUrl;

    public CardPageController(com.tailtopia.profile.service.ProfileService profileService,
            ContentService contentService, AccountQueryService accountQueryService,
            TimelineService timelineService,
            @Value("${petgo.card.app-download-url:https://petgo.example/download}") String downloadUrl,
            @Value("${petgo.card.ios-url:https://apps.apple.com/app/petgo}") String iosUrl,
            @Value("${petgo.card.android-url:https://play.google.com/store/apps/details?id=com.tailtopia}")
                    String androidUrl,
            @Value("${petgo.card.public-base-url:}") String publicBaseUrl) {
        this.profiles = profileService::findByCardToken;
        this.contentService = contentService;
        this.accountQueryService = accountQueryService;
        this.timelineService = timelineService;
        this.downloadUrl = downloadUrl;
        this.iosUrl = iosUrl;
        this.androidUrl = androidUrl;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** 极薄 facade，仅暴露 findByCardToken，避免控制器直依赖整个 service 表面。 */
    @FunctionalInterface
    interface ProfileServiceFacade {
        Optional<PetProfile> findByCardToken(String token);
    }

    @GetMapping("/p/{cardToken}")
    public String card(@PathVariable String cardToken, Model model, HttpServletResponse response) {
        Optional<PetProfile> opt = profiles.findByCardToken(cardToken);
        if (opt.isEmpty()) {
            return gone(model, response);
        }
        PetProfile profile = opt.get();
        // 账号注销 → 与不存在同一失效页（防枚举）。
        if (!accountQueryService.isActive(profile.getOwnerId())) {
            return gone(model, response);
        }

        long ownerId = profile.getOwnerId();
        // ① Hero
        model.addAttribute("name", profile.getName());
        model.addAttribute("breed", profile.getBreed());
        model.addAttribute("intro", profile.getIntro());
        model.addAttribute("avatarUrl", AliyunOssClient.exifStrippedDeliveryUrl(profile.getAvatarUrl()));
        model.addAttribute("ownerNickname", ownerNickname(ownerId));
        model.addAttribute("companionDays", companionDays(profile.getCreatedAt(), Instant.now()));

        // ③ 故事数字 + ②④ 里程碑零态（milestoneCompleted=0 → 隐藏徽章条/动态/「里程碑完成」项）。
        ArchiveStatsResponse stats = timelineService.getStats(ownerId);
        model.addAttribute("happyCount", stats.happyMomentCount());
        model.addAttribute("consultCount", stats.consultCount());
        model.addAttribute("milestoneCompleted", stats.milestoneCompleted());
        model.addAttribute("hasMilestones", stats.milestoneCompleted() > 0);

        // ⑤ 快乐时刻照片流（按 event_date 倒序，AC7）。
        List<CardMoment> moments = buildMoments(ownerId);
        model.addAttribute("moments", moments);
        model.addAttribute("hasMoments", !moments.isEmpty());

        // OG / Twitter：标题「[宠物名] 的成长故事」。
        String ogTitle = profile.getName() + " 的成长故事";
        model.addAttribute("ogTitle", ogTitle);
        String ogImage = profile.getOgImageUrl() != null
                ? profile.getOgImageUrl()
                : AliyunOssClient.exifStrippedDeliveryUrl(profile.getAvatarUrl());
        model.addAttribute("ogImageUrl", ogImage);
        model.addAttribute("pageUrl", publicBaseUrl + "/p/" + cardToken);

        // ⑥ 双 CTA 平台分流（已装 App 经深链直开档案，未装跳商店；真机分流见模板 JS）。
        model.addAttribute("downloadUrl", downloadUrl);
        model.addAttribute("iosUrl", iosUrl);
        model.addAttribute("androidUrl", androidUrl);
        model.addAttribute("deepLink", "petgo://card/" + cardToken);
        return "card";
    }

    /** 陪伴天数 = 当前日期 − 档案创建日期（按 UTC 天数，≥0）。纯函数便于 L0 测。 */
    public static long companionDays(Instant createdAt, Instant now) {
        if (createdAt == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(createdAt, now);
        return Math.max(0, days);
    }

    private String ownerNickname(long ownerId) {
        var view = accountQueryService.findAuthorViews(List.of(ownerId)).get(ownerId);
        return view != null ? view.nickname() : null;
    }

    private List<CardMoment> buildMoments(long ownerId) {
        List<GrowthMomentView> raw = contentService.findRecentGrowthMomentsByEventDate(ownerId, MAX_MOMENTS);
        List<CardMoment> out = new ArrayList<>(raw.size());
        for (GrowthMomentView m : raw) {
            List<String> stripped = new ArrayList<>();
            if (m.imageUrls() != null) {
                for (String url : m.imageUrls()) {
                    stripped.add(AliyunOssClient.exifStrippedDeliveryUrl(url));
                }
            }
            out.add(new CardMoment(stripped, m.text()));
        }
        return out;
    }

    private String gone(Model model, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        model.addAttribute("downloadUrl", downloadUrl);
        return "card_gone";
    }

    /** Thymeleaf 安全视图（getter 访问）：一条快乐时刻的对外图与文字。 */
    public static class CardMoment {
        private final List<String> imageUrls;
        private final String text;

        public CardMoment(List<String> imageUrls, String text) {
            this.imageUrls = imageUrls;
            this.text = text;
        }

        public List<String> getImageUrls() {
            return imageUrls;
        }

        public String getText() {
            return text;
        }
    }
}
