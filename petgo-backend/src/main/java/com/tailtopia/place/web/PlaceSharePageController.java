package com.tailtopia.place.web;

import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlacePhoto;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.dto.PlaceDetailResponse;
import com.tailtopia.place.repository.PlacePhotoRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.shared.media.AliyunOssClient;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 场所对外 H5（V1.3.0 batch-b1 Story 1.10 · AD-5）。Thymeleaf 直出
 * {@code GET /place/{publicToken}}，<b>公开无需鉴权</b>（SecurityConfig 同 {@code /p/}、
 * {@code /m/}、{@code /c/} 一并放行）。
 *
 * <h2>🔴 路径里是不可枚举 token，不是场所名也不是自增 id（AC2 / NFR-1 / AD-1 Rule 3）</h2>
 * 用名字或序号拼链接，等于让任何人按名字 / 按序号把全站场所爬一遍。
 *
 * <h2>🔴 本页有 og:image，这是对名片页 / 里程碑页边界的**有意例外**（AC4）</h2>
 * {@code /p/}（名片）与 {@code /m/}（里程碑）两页**刻意不放** {@code og:image} ——
 * 那两页的图是**宠物与主人的私人照片**，交给第三方平台抓取并长期缓存不合适。
 * <p><b>场所照片不同：它拍的是一家店的门口、座位区、菜单</b> —— 不含个人与宠物隐私，
 * 而这页的全部意义就是"发到 WhatsApp 群里让朋友一眼看到这地方长什么样"。没有预览图，
 * 那条链接在群里就是一行蓝字。
 * <p>⚠️ <b>所以这不是漏做，不要"顺手统一"成不放图</b>；要改回去先回 AD-5 改口径。
 *
 * <h2>🔴 但是：审核**没过**的照片绝不下发 og:image（AC5，2026-09-15 拍板）</h2>
 * 社交平台会**主动抓取并缓存**预览图。审核放行前就给图，等于任何人都能造一个场所、
 * 传一张违规图、把链接甩进群 —— 等运营下架时，<b>预览卡已经躺在几百个聊天记录里，
 * 而且不随下架消失</b>。
 * <p><b>H5 页面会变，预览卡不会 —— 这是两个东西。</b>
 * <p>所以 {@code og:image} 只取 <b>{@code og_eligible}</b> 的照片 ——
 * 那比"对外可见"更严：标记时那批在三方 {@code RISKY} / {@code DEGRADED}
 * （"有点像" / "压根没查成"）下**也会落 VISIBLE 对外展示**（Story 1.3 的先发后审口径，不改），
 * 但拿不到 og 资格。<b>页面上展示是一回事，交给社交平台永久缓存是另一回事。</b>
 * <p>没有合格照片时，分享出去的卡片仍有场所名 / 邀约文案 / 地址，只是没有大图。
 *
 * <h2>下架 / 不存在（AC7）</h2>
 * 统一复用名片失效页 {@code card_gone} + 404 + noindex，<b>绝不区分原因</b>（防枚举）。
 * ⚠️ <b>链接本身长期有效</b> —— token 不回收、不重发；下架只是让这一次访问落到失效页。
 */
@Controller
public class PlaceSharePageController {

    /**
     * OG 预览图宽度（物理像素）。
     *
     * <p>1200 是各家社交平台大卡预览的通行宽度；同时这条 {@code x-oss-process} 里的
     * {@code format,jpg} 重编码**顺带去掉 EXIF/GPS**（E4）——
     * 对外分发的公开桶图一律经此。
     */
    static final int OG_IMAGE_WIDTH_PX = 1200;

    private final PlaceRepository places;
    private final PlacePhotoRepository photos;
    private final String downloadUrl;
    private final String iosUrl;
    private final String androidUrl;

    public PlaceSharePageController(PlaceRepository places, PlacePhotoRepository photos,
            @Value("${petgo.card.app-download-url:https://petgo.example/download}") String downloadUrl,
            @Value("${petgo.card.ios-url:https://apps.apple.com/app/petgo}") String iosUrl,
            @Value("${petgo.card.android-url:https://play.google.com/store/apps/details?id=com.tailtopia.app}")
                    String androidUrl) {
        this.places = places;
        this.photos = photos;
        this.downloadUrl = downloadUrl;
        this.iosUrl = iosUrl;
        this.androidUrl = androidUrl;
    }

    @GetMapping("/place/{token}")
    public String placePage(@PathVariable String token, Model model,
            HttpServletResponse response) {
        Optional<Place> opt = places.findByPublicTokenAndStatus(token, PlaceStatus.ACTIVE);
        if (opt.isEmpty()) {
            // 🔴 下架与从未存在**同一个响应**（AC7）：可区分就等于给出「这个 token 曾经存在」。
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("downloadUrl", downloadUrl);
            return "card_gone";
        }
        Place place = opt.get();

        // 🔒 只取**对外可见**的照片：这里是给全世界看的页面，没有"上传者自视豁免"那回事。
        List<PlacePhoto> visible = photos.findVisible(place.getId(), false, null);
        List<String> imageUrls = visible.stream()
                .map(p -> AliyunOssClient.exifStrippedThumbUrl(
                        p.getUrl(), PlaceDetailResponse.DETAIL_PHOTO_WIDTH_PX))
                .toList();

        // 页面语言恒印尼语（与名片 / 里程碑 / 单条内容三页同口径：H5 无登录态，拿不到语言偏好）。
        model.addAttribute("placeName", place.getName());
        model.addAttribute("typeLabel", typeLabel(place));
        model.addAttribute("tagLabels", tagLabels(place));
        model.addAttribute("hasTags", !tagLabels(place).isEmpty());
        model.addAttribute("addressText", place.getAddressText());
        model.addAttribute("description", place.getDescription() == null ? "" : place.getDescription());
        model.addAttribute("hasDescription",
                place.getDescription() != null && !place.getDescription().isBlank());
        model.addAttribute("images", imageUrls);
        model.addAttribute("hasImages", !imageUrls.isEmpty());
        model.addAttribute("photoCount", imageUrls.size());

        model.addAttribute("ogTitle", place.getName());
        model.addAttribute("ogDescription", ogDescription(place));
        // 🔴 AC5：只有**干净过审**的照片才能当 og:image（理由见类注释 —— 预览卡会被缓存、
        // 运营下架也撤不回来）。
        // ⚠️ 判据是 `isOgEligible()` 而**不是** `VISIBLE`：标记时那批在三方 RISKY / DEGRADED
        //    （"有点像" / "压根没查成"）下也会落 VISIBLE 对外展示（Story 1.3 的先发后审口径）——
        //    页面上展示是一回事，把它交给社交平台永久缓存是另一回事。
        String ogImage = visible.stream()
                .filter(PlacePhoto::isOgEligible)
                .findFirst()
                .map(p -> AliyunOssClient.exifStrippedThumbUrl(p.getUrl(), OG_IMAGE_WIDTH_PX))
                .orElse(null);
        model.addAttribute("ogImage", ogImage);
        model.addAttribute("hasOgImage", ogImage != null);

        model.addAttribute("deeplink", "tailtopia://place/" + place.getPublicToken());
        model.addAttribute("downloadCta", "Buka di aplikasi TailTopia");
        model.addAttribute("downloadUrl", downloadUrl);
        model.addAttribute("iosUrl", iosUrl);
        model.addAttribute("androidUrl", androidUrl);
        return "place_share";
    }

    /** 邀约文案（UI 稿 A9）+ 地址，作为 OG 描述。 */
    private static String ogDescription(Place place) {
        return "Temanmu mengajak kamu nongkrong di sini! " + place.getAddressText();
    }

    /** 类型文案（印尼语，与 App 的 placeType* 一致）。 */
    private static String typeLabel(Place place) {
        if (place.getType() == null) {
            return "";
        }
        return switch (place.getType()) {
            case CAFE -> "Kafe";
            case RESTAURANT -> "Restoran";
            case PARK -> "Taman";
            case MALL -> "Mal";
            case HOTEL -> "Hotel & penginapan";
            case PET_SERVICE -> "Layanan hewan";
            case OTHER -> "Lainnya";
        };
    }

    /** 宠物友好标签文案（印尼语，与 App 的 placeTag* 一致）。 */
    private static List<String> tagLabels(Place place) {
        if (place.getTags() == null) {
            return List.of();
        }
        return place.getTags().stream().map(PlaceSharePageController::tagLabel).toList();
    }

    private static String tagLabel(PlaceTag tag) {
        return switch (tag) {
            case PETS_ALLOWED_INSIDE -> "Boleh masuk";
            case OUTDOOR_SEATING -> "Area outdoor";
            case PET_MENU -> "Menu hewan";
            case PET_PLAY_AREA -> "Area bermain";
            case LEASH_REQUIRED -> "Wajib tali";
            case LARGE_DOG_FRIENDLY -> "Ramah anjing besar";
        };
    }
}
