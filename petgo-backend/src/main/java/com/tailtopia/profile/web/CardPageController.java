package com.tailtopia.profile.web;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.GrowthMomentView;
import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.MilestoneDefinition;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.dto.ArchiveStatsResponse;
import com.tailtopia.profile.dto.MilestoneItemResponse;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.profile.service.OgImageService;
import com.tailtopia.profile.service.TimelineService;
import com.tailtopia.shared.media.AliyunOssClient;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * 取多少条快乐时刻（按 event_date 倒序）。
     *
     * <p>⚠️ V1.1.6 Story 1.2 起从 5 提到 6：页面有<b>两处</b>贴照片的地方 ——
     * 顶部照片簇 2 张 + 「快乐时刻」拼贴 4 张（视觉稿 E1 就是这个配置）。
     * 停在 5 会让拼贴区最多只有 3 张、永远填不满，且**无图的条目也占名额**
     * （L2 实测：一条没有配图的老记录挤掉了拼贴区的一格）。
     */
    /** 顶部照片簇里贴的照片数（视觉稿 E1：主张之外还有右后、左下前两张）。 */
    private static final int CLUSTER_PHOTOS = 2;
    /** 「快乐时刻」拼贴区的照片数（视觉稿 E1：4 张不同尺寸角度交叠）。 */
    private static final int COLLAGE_PHOTOS = 4;
    private static final int MAX_MOMENTS = CLUSTER_PHOTOS + COLLAGE_PHOTOS;
    /** 具名徽章展示上限（视觉稿 E1：2 个具名 chip + 2 个空槽，其余靠「N / 总数」计数体现）。 */
    private static final int MAX_BADGES = 2;

    private final ProfileServiceFacade profiles;
    private final ContentService contentService;
    private final AccountQueryService accountQueryService;
    private final TimelineService timelineService;
    private final MilestoneService milestoneService;
    private final OgImageService ogImageService;
    private final String downloadUrl;
    private final String iosUrl;
    private final String androidUrl;
    private final String publicBaseUrl;

    public CardPageController(com.tailtopia.profile.service.ProfileService profileService,
            ContentService contentService, AccountQueryService accountQueryService,
            TimelineService timelineService, MilestoneService milestoneService,
            OgImageService ogImageService,
            @Value("${petgo.card.app-download-url:https://petgo.example/download}") String downloadUrl,
            @Value("${petgo.card.ios-url:https://apps.apple.com/app/petgo}") String iosUrl,
            @Value("${petgo.card.android-url:https://play.google.com/store/apps/details?id=com.tailtopia.app}")
                    String androidUrl,
            @Value("${petgo.card.public-base-url:}") String publicBaseUrl) {
        this.profiles = profileService::findByCardToken;
        this.contentService = contentService;
        this.accountQueryService = accountQueryService;
        this.timelineService = timelineService;
        this.milestoneService = milestoneService;
        this.ogImageService = ogImageService;
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
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // ① Hero
        model.addAttribute("name", profile.getName());
        model.addAttribute("breed", profile.getBreed());
        model.addAttribute("intro", profile.getIntro());
        model.addAttribute("avatarUrl", AliyunOssClient.exifStrippedDeliveryUrl(profile.getAvatarUrl()));
        String nickname = ownerNickname(ownerId);
        model.addAttribute("ownerNickname", nickname);
        model.addAttribute("companionDays", companionDays(profile.getCreatedAt(), Instant.now()));
        // 元信息行（V1.1.6 Story 1.2）：品种 · 性别 · 年龄 · bersama 主人。四段全可缺，逐段判空拼接。
        // 性别来自 Story 1.1（可空）；年龄由生日推出（可空）。全缺则为 null → 模板整行不渲染。
        model.addAttribute("metaLine", metaLine(profile.getBreed(), profile.getSex(),
                ageText(profile.getBirthday(), today), nickname));

        // ③ 故事数字 + ②④ 里程碑零态（milestoneCompleted=0 → 隐藏徽章条/动态/「里程碑完成」项）。
        ArchiveStatsResponse stats = timelineService.getStats(ownerId);
        model.addAttribute("happyCount", stats.happyMomentCount());
        model.addAttribute("consultCount", stats.consultCount());
        model.addAttribute("milestoneCompleted", stats.milestoneCompleted());
        model.addAttribute("hasMilestones", stats.milestoneCompleted() > 0);
        // ② 里程碑收藏区（V1.1.6 Story 1.2）：完成数 / 总数 + 具名徽章 + 锁定位 + 「+N」。
        addMilestoneSection(model, profile, stats.milestoneCompleted(), today);

        // ⑤ 快乐时刻照片流（按 event_date 倒序，AC7）。
        // ⚠️ 多取一些再过滤：**无图的条目不该占名额**（L2 实测踩到 —— 一条没配图的老记录
        // 挤掉了拼贴区的一格）。取 2 倍上限足够覆盖零星缺图，仍是一次查询、无额外开销。
        List<CardMoment> moments = buildMoments(ownerId, profile.getId());
        model.addAttribute("moments", moments);
        model.addAttribute("hasMoments", !moments.isEmpty());
        // V1.1.6 Story 1.2：页面有**两处**贴照片的地方 —— 顶部照片簇与「快乐时刻」拼贴。
        // ⚠️ 两处必须用**不同的**照片（视觉稿 E1 就是这么画的），且各自的位置样式按
        // **实际渲染序号**分配，故在这里切好再交给模板，不让模板用原始下标去猜。
        List<CardMoment> withPhoto = moments.stream()
                .filter(m -> m.getImageUrls() != null && !m.getImageUrls().isEmpty())
                .toList();
        model.addAttribute("clusterPhotos",
                withPhoto.stream().limit(CLUSTER_PHOTOS).toList());
        model.addAttribute("collagePhotos",
                withPhoto.stream().skip(CLUSTER_PHOTOS).limit(COLLAGE_PHOTOS).toList());

        // OG / Twitter：标题本地化为印尼语（页面 lang=id；修 20260702-208 原硬编码中文「…的成长故事」）。
        String ogTitle = "Kisah tumbuh kembang " + profile.getName();
        model.addAttribute("ogTitle", ogTitle);
        // og:image 优先用预渲染 1200×630 PNG（WhatsApp 等严格抓取器缺尺寸会静默丢图）。
        // 大图原仅在编辑档案时生成 → 从未编辑过的档案为 null，WhatsApp 遂丢图（Instagram 宽容照显头像）。
        // 此处懒补生成一次（同时覆盖存量老档案 + 新建档案），使首次抓取即拿到带尺寸的图；
        // 生成失败（如 OSS 未配置/抽风）静默回退头像，绝不阻断页面。
        String wideUrl = profile.getOgImageUrl();
        if (wideUrl == null) {
            try {
                wideUrl = ogImageService.regenerate(profile);
            } catch (RuntimeException e) {
                wideUrl = null; // 回退头像
            }
        }
        boolean ogImageWide = wideUrl != null;
        String ogImage = ogImageWide
                ? wideUrl
                : AliyunOssClient.exifStrippedDeliveryUrl(profile.getAvatarUrl());
        model.addAttribute("ogImageUrl", ogImage);
        // 仅预渲染大图有确定的 1200×630 尺寸；回退头像尺寸未知，不输出 width/height 以免误导抓取器。
        model.addAttribute("ogImageWide", ogImageWide);
        model.addAttribute("pageUrl", publicBaseUrl + "/p/" + cardToken);

        // ⑥ 双 CTA 平台分流（已装 App 经深链直开档案，未装跳商店；真机分流见模板 JS）。
        model.addAttribute("downloadUrl", downloadUrl);
        model.addAttribute("iosUrl", iosUrl);
        model.addAttribute("androidUrl", androidUrl);
        model.addAttribute("deepLink", "tailtopia://card/" + cardToken);
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

    /**
     * 元信息行：{@code 品种 · 性别 · 年龄 · bersama 主人}（V1.1.6 Story 1.2，E1 屏）。
     *
     * <p>🛡 <b>四段全都可能缺</b>（E2 屏就只有「品种 · 性别」两段），故逐段判空后再用 {@code · } 连接。
     * 拼错的表现是页面上出现 {@code 「Kucing · · bersama Rina」} 这种连续分隔符。
     * 四段全缺 → 返回 {@code null}，模板整行不渲染（而不是渲染一个空行）。
     *
     * <p>纯函数便于 L0 测（{@code CardPageTextTest}）。
     */
    public static String metaLine(String breed, PetSex sex, String ageText, String ownerNickname) {
        List<String> parts = new ArrayList<>(4);
        addIfPresent(parts, breed);
        if (sex != null) {
            addIfPresent(parts, switch (sex) {
                case MALE -> "Jantan";
                case FEMALE -> "Betina";
            });
        }
        addIfPresent(parts, ageText);
        if (ownerNickname != null && !ownerNickname.isBlank()) {
            parts.add("bersama " + ownerNickname.trim());
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    /**
     * 年龄文案（印尼语）：{@code 2 tahun} / {@code 6 bulan} / {@code 3 hari} / {@code baru lahir}。
     *
     * <p>生日可空 → {@code null}（该段不渲染）。不足一岁说月、不足一月说天，
     * 避免出现「0 tahun」这种读起来像出错的文案。
     * 生日在未来（脏数据）同样返回 {@code null}，不显示负数。
     */
    public static String ageText(java.time.LocalDate birthday, java.time.LocalDate today) {
        if (birthday == null || birthday.isAfter(today)) {
            return null;
        }
        java.time.Period p = java.time.Period.between(birthday, today);
        if (p.getYears() > 0) {
            return p.getYears() + " tahun";
        }
        if (p.getMonths() > 0) {
            return p.getMonths() + " bulan";
        }
        int days = p.getDays();
        return days > 0 ? days + " hari" : "baru lahir";
    }

    /**
     * 相对时间（印尼语大写，E1 屏的 {@code 3 HARI LALU}）。
     *
     * <p>🛡 H5 全篇印尼语，此处漏一档就是页面上蹦出中文 —— {@code CardPageTextTest} 遍历 0~800 天
     * 逐个断言不含中日韩字符。未来时间（时钟偏差）夹到「今天」，不出现负数。
     */
    public static String relativeDays(java.time.LocalDate when, java.time.LocalDate today) {
        long days = ChronoUnit.DAYS.between(when, today);
        if (days <= 0) {
            return "HARI INI";
        }
        if (days == 1) {
            return "KEMARIN";
        }
        if (days < 7) {
            return days + " HARI LALU";
        }
        if (days < 30) {
            return (days / 7) + " MINGGU LALU";
        }
        if (days < 365) {
            return (days / 30) + " BULAN LALU";
        }
        return (days / 365) + " TAHUN LALU";
    }

    /**
     * 里程碑收藏区（V1.1.6 Story 1.2 · AC2/AC4）。
     *
     * <p>下发：总数（<b>按物种</b>：猫 31 / 狗 31 / 通用 16，取自 {@link MilestoneCatalog}，
     * <b>不是视觉稿里那个示意的 30</b>）· 最近完成的具名徽章 · 未完成数（「+N」）· 最新动态的相对时间。
     *
     * <p>🔴 <b>本页对匿名公众开放，所以绝不能在这里触发写库。</b>
     * {@code MilestoneService.getMilestones()} 标了 {@code @Transactional}，且读路径发现 roster 缺失时
     * 会 <b>lazy 物化</b>（{@code assignRoster} → 写 {@code pet_milestones}）。若无条件调用，
     * 每个陌生人打开一次分享页都可能触发一次写库。
     * <b>故只在 {@code completed > 0} 时才调它</b> —— 完成数大于 0 意味着 roster 必然已经物化过，
     * 那条 lazy 分支走不到。零里程碑的宠物（E2 降级态）本就不需要清单，总数直接从常量目录拿。
     * {@code CardPageMilestoneIntegrationTest} 有一条用例专门钉住这件事。
     */
    private void addMilestoneSection(Model model, PetProfile profile, long completed, LocalDate today) {
        int total = MilestoneCatalog.forType(profile.getPetType()).size();
        model.addAttribute("milestoneTotal", total);
        // 「+N」= 尚未完成的数量（视觉稿 E1：7/30 对应 +23）。
        model.addAttribute("milestoneMore", Math.max(0, total - completed));

        if (completed <= 0) {
            // E2 零态：无具名徽章、无最新动态。**不调 getMilestones()**（见上方 🔴）。
            model.addAttribute("badges", List.of());
            model.addAttribute("latestMilestoneAgo", null);
            return;
        }

        List<MilestoneItemResponse> done = milestoneService.getMilestones(profile.getOwnerId())
                .groups().stream()
                .flatMap(g -> g.items().stream())
                .filter(MilestoneItemResponse::completed)
                .sorted(Comparator.comparing(MilestoneItemResponse::completedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        // 具名徽章：最近完成的前 N 个（视觉稿 E1 展示 2 个具名 + 2 个空槽）。
        // ⚠️ 标题取印尼语 titleId，**不是** MilestoneItemResponse.title（那是中文，AC3 禁止出现在本页）。
        List<String> badges = done.stream()
                .limit(MAX_BADGES)
                .map(i -> {
                    MilestoneDefinition def = MilestoneCatalog.byCode(i.code());
                    return def != null ? def.titleId() : i.code(); // 未知 code 兜底回 code，绝不回退中文
                })
                .toList();
        model.addAttribute("badges", badges);

        Instant latest = done.isEmpty() ? null : done.get(0).completedAt();
        model.addAttribute("latestMilestoneAgo",
                latest == null ? null : relativeDays(latest.atZone(ZoneOffset.UTC).toLocalDate(), today));
    }

    private String ownerNickname(long ownerId) {
        var view = accountQueryService.findAuthorViews(List.of(ownerId)).get(ownerId);
        return view != null ? view.nickname() : null;
    }

    private List<CardMoment> buildMoments(long ownerId, long petId) {
        List<GrowthMomentView> raw =
                contentService.findRecentGrowthMomentsByEventDate(ownerId, petId, MAX_MOMENTS * 2);
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
