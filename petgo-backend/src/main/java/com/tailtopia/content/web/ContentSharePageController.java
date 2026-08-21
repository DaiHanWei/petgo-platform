package com.tailtopia.content.web;

import com.tailtopia.content.dto.SharedPostResponse;
import com.tailtopia.content.service.ContentShareService;
import com.tailtopia.content.service.PostSharePageAnalytics;
import com.tailtopia.shared.analytics.AnonymousVisitorId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 单条内容对外 H5（Story 9.3 · FR-73）。Thymeleaf 直出 {@code GET /c/{shareToken}}，
 * <b>公开无需鉴权</b>（SecurityConfig 同 {@code /p/**}、{@code /m/**} 放行）。
 *
 * <p>🔴 <b>这是与名片页 {@code /p/} 完全不同的落地页</b>（AD-15 Rule 5）：名片页展示整本档案的只读视图，
 * 本页<b>只有被分享的那一条</b>。
 *
 * <p>🛡 页面上<b>不存在</b>任何通往该宠物其它内容的路径：没有档案链接、没有"看更多"、
 * 没有作者主页入口。而且边界不只画在页面上 —— {@link SharedPostResponse} 这个投影里
 * <b>压根没有</b> postId / authorId / petId / cardToken，所以将来谁想加个"更多"入口也拿不到把手。
 *
 * <p>失效（token 不存在 / 内容已删 / 审核挂起 / 被举报下架 / 作者注销）→ 统一复用名片失效页
 * {@code card_gone} + 404 + noindex，<b>绝不区分原因</b>（防枚举）。
 *
 * <p>无 OG 预览图：沿用名片 / 里程碑两页的既有边界（不把宠物图交给第三方抓取与缓存）。
 * 二维码扫到的观看者看到的是页面本身，不是 IM 预览缩略图，所以这个取舍不影响主链路。
 */
@Controller
public class ContentSharePageController {

    private final ContentShareService shareService;
    private final PostSharePageAnalytics analytics;
    private final String downloadUrl;
    private final String iosUrl;
    private final String androidUrl;

    public ContentSharePageController(ContentShareService shareService,
            PostSharePageAnalytics analytics,
            @Value("${petgo.card.app-download-url:https://petgo.example/download}") String downloadUrl,
            @Value("${petgo.card.ios-url:https://apps.apple.com/app/petgo}") String iosUrl,
            @Value("${petgo.card.android-url:https://play.google.com/store/apps/details?id=com.tailtopia.app}")
                    String androidUrl) {
        this.shareService = shareService;
        this.analytics = analytics;
        this.downloadUrl = downloadUrl;
        this.iosUrl = iosUrl;
        this.androidUrl = androidUrl;
    }

    @GetMapping("/c/{shareToken}")
    public String sharedPost(@PathVariable String shareToken, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        // 埋点 E-14（Story 10.1）：匿名访客标识复用名片页那个 tt_vid cookie ——
        // 同一个人先点名片、后点某条内容，在看板上要串成同一条路径。
        String visitorId = AnonymousVisitorId.resolveOrIssue(request, response);
        // 🛡 **在成功/失效分叉之前报**：失效也是要看的数（见 PostSharePageAnalytics 注释）。
        analytics.linkOpened(visitorId, request);

        Optional<SharedPostResponse> opt = shareService.findSharedPost(shareToken);
        if (opt.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("downloadUrl", downloadUrl);
            return "card_gone";
        }
        SharedPostResponse post = opt.get();

        // 页面语言恒印尼语（与名片 / 里程碑两页同口径：H5 无登录态、拿不到用户语言偏好）。
        model.addAttribute("authorName",
                post.authorDeleted() || post.authorNickname() == null
                        ? "Pengguna dihapus"
                        : post.authorNickname());
        model.addAttribute("authorAvatarUrl", post.authorAvatarUrl());
        model.addAttribute("hasAvatar", post.authorAvatarUrl() != null && !post.authorAvatarUrl().isBlank());
        model.addAttribute("typeLabel", typeLabel(post.type()));
        model.addAttribute("typeColor", typeColor(post.type()));
        model.addAttribute("body", post.body() == null ? "" : post.body());
        model.addAttribute("hasBody", post.body() != null && !post.body().isBlank());
        model.addAttribute("images", post.imageUrls());
        model.addAttribute("hasImages", !post.imageUrls().isEmpty());
        model.addAttribute("dateLabel", dateLabel(post));
        model.addAttribute("ogTitle", "TailTopia");
        model.addAttribute("downloadCta", "Buat arsip untuk hewanku");
        model.addAttribute("downloadUrl", downloadUrl);
        model.addAttribute("iosUrl", iosUrl);
        model.addAttribute("androidUrl", androidUrl);
        return "content_share";
    }

    /** 类型 chip 文案（印尼语，与 App 的 mePostType* 一致）。 */
    private static String typeLabel(String type) {
        return switch (type) {
            case "GROWTH_MOMENT" -> "Diary";
            case "KNOWLEDGE" -> "Edukasi & Tips";
            default -> "Momen";
        };
    }

    /** 类型配色（与 App 一致：Diary 绿 / Tips 黄 / Momen 紫）。 */
    private static String typeColor(String type) {
        return switch (type) {
            case "GROWTH_MOMENT" -> "#0E7A4D";
            case "KNOWLEDGE" -> "#8A5A00";
            default -> "#845EC9";
        };
    }

    private static final String[] MONTHS_ID = {
        "Januari", "Februari", "Maret", "April", "Mei", "Juni",
        "Juli", "Agustus", "September", "Oktober", "November", "Desember"
    };

    /** 发布日期（WIB，与 App 展示同一时区口径）。 */
    private static String dateLabel(SharedPostResponse post) {
        if (post.createdAt() == null) {
            return "";
        }
        var d = post.createdAt().atZone(ZoneId.of("Asia/Jakarta")).toLocalDate();
        return d.getDayOfMonth() + " " + MONTHS_ID[d.getMonthValue() - 1] + " " + d.getYear();
    }
}
