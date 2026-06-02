package com.petgo.profile.web;

import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.service.ContentService;
import com.petgo.content.service.GrowthMomentView;
import com.petgo.profile.domain.PetProfile;
import com.petgo.shared.media.AliyunOssClient;
import jakarta.servlet.http.HttpServletResponse;
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
 * <p>仅展示头像/名字/品种/介绍 + 最近 5 条成长日历快乐时刻（倒序），**不含日常/科普/健康事件**。
 * 失效（token 不存在 / 账号注销）→ 统一 404 友好失效页 + noindex（防枚举，不泄漏 token 曾否存在）。
 * 对外图均经 E4 服务端去 EXIF 分发。
 */
@Controller
public class CardPageController {

    private static final int MAX_MOMENTS = 5;

    private final ProfileServiceFacade profiles;
    private final ContentService contentService;
    private final AccountQueryService accountQueryService;
    private final String downloadUrl;
    private final String publicBaseUrl;

    public CardPageController(com.petgo.profile.service.ProfileService profileService,
            ContentService contentService, AccountQueryService accountQueryService,
            @Value("${petgo.card.app-download-url:https://petgo.example/download}") String downloadUrl,
            @Value("${petgo.card.public-base-url:}") String publicBaseUrl) {
        this.profiles = profileService::findByCardToken;
        this.contentService = contentService;
        this.accountQueryService = accountQueryService;
        this.downloadUrl = downloadUrl;
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
        // 账号注销 → 与不存在/删除同一失效页（防枚举）。
        if (!accountQueryService.isActive(profile.getOwnerId())) {
            return gone(model, response);
        }

        model.addAttribute("name", profile.getName());
        model.addAttribute("breed", profile.getBreed());
        model.addAttribute("intro", profile.getIntro());
        model.addAttribute("avatarUrl", AliyunOssClient.exifStrippedDeliveryUrl(profile.getAvatarUrl()));
        // og:image 优先用预渲染静态图；缺省回退到去 EXIF 的头像。
        String ogImage = profile.getOgImageUrl() != null
                ? profile.getOgImageUrl()
                : AliyunOssClient.exifStrippedDeliveryUrl(profile.getAvatarUrl());
        model.addAttribute("ogImageUrl", ogImage);
        model.addAttribute("pageUrl", publicBaseUrl + "/p/" + cardToken);
        model.addAttribute("downloadUrl", downloadUrl);
        model.addAttribute("moments", buildMoments(profile.getOwnerId()));
        return "card";
    }

    private List<CardMoment> buildMoments(long ownerId) {
        List<GrowthMomentView> raw = contentService.findGrowthMoments(ownerId, null, MAX_MOMENTS);
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
