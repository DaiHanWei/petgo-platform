package com.tailtopia.place.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlacePhoto;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.repository.PlacePhotoRepository;
import com.tailtopia.place.repository.PlaceRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * L0（mock 仓储，无 Spring MVC / 无 DB）：场所对外 H5
 * （V1.3.0 batch-b1 Story 1.10 · AC2/AC4/AC5/AC7）。
 *
 * <p>AC3（页面内容）与 AC6（真机唤起）是 L2，这里守的是**模型与门控** ——
 * 尤其是 AC5 那条：`og:image` 只在有**已过审**照片时才下发。
 */
class PlaceSharePageControllerTest {

    private PlaceRepository places;
    private PlacePhotoRepository photos;
    private PlaceSharePageController controller;
    private HttpServletResponse response;
    private com.tailtopia.place.service.PlaceCommentQueryService placeComments;
    private com.tailtopia.place.service.PlaceAttitudeCounters attitudeCounters;

    @BeforeEach
    void setUp() {
        places = Mockito.mock(PlaceRepository.class);
        photos = Mockito.mock(PlacePhotoRepository.class);
        response = Mockito.mock(HttpServletResponse.class);
        placeComments = Mockito.mock(com.tailtopia.place.service.PlaceCommentQueryService.class);
        attitudeCounters = Mockito.mock(com.tailtopia.place.service.PlaceAttitudeCounters.class);
        when(attitudeCounters.countsOf(anyLong()))
                .thenReturn(com.tailtopia.place.service.PlaceAttitudeCounters.Counts.ZERO);
        controller = new PlaceSharePageController(places, photos,
                // 照片 key → URL（对齐 D5）：真实实例，只用到 publicUrlOf。
                new com.tailtopia.place.service.PlacePhotoService(places, photos, null,
                        com.tailtopia.place.PlaceTestSupport.oss()),
                placeComments, attitudeCounters,
                "https://dl.test", "https://ios.test", "https://play.test");
        when(photos.findVisible(anyLong(), anyBoolean(), any())).thenReturn(List.of());
        when(places.resolveForView("tok"))
                .thenReturn(Optional.of(withId(place(), 42L)));
    }

    // ===== AC7 下架 / 不存在 =====

    /**
     * 🔴 下架与从未存在**同一个响应**：可区分就等于给出「这个 token 曾经存在」。
     * 复用名片失效页 + 404（模板里带 noindex）。
     */
    @Test
    void takenDownAndUnknownTokensAreIndistinguishable() {
        when(places.resolveForView("gone"))
                .thenReturn(Optional.empty());

        Model a = new ConcurrentModel();
        assertThat(controller.placePage("gone", a, response)).isEqualTo("card_gone");
        Mockito.verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);

        // 失效页上**不带任何场所信息** —— 下架后不泄漏原内容；只有下载链接与三条场所文案常量。
        assertThat(a.asMap()).containsOnlyKeys("downloadUrl", "goneTitle", "goneSubtitle", "goneCta");
        assertThat(a.getAttribute("goneTitle")).isEqualTo("Tempat ini sudah tidak ada");
        // 🔴 防枚举：副标题必须同时给出两种原因（「atau」），不暴露到底是删了还是从未存在。
        assertThat((String) a.getAttribute("goneSubtitle")).contains("dihapus atau tautannya");
    }

    // ===== A9 社交佐证 / 缩略图 =====

    /** 评论数按**无登录态**口径取（viewerId=null → 只数对外可见的），推荐数与详情页同一套计数器。 */
    @Test
    void socialProofUsesPublicCounts() {
        when(placeComments.countForPlace(42L, null)).thenReturn(3L);
        when(attitudeCounters.countsOf(42L))
                .thenReturn(new com.tailtopia.place.service.PlaceAttitudeCounters.Counts(12, 5));

        Model model = new ConcurrentModel();
        controller.placePage("tok", model, response);

        assertThat(model.getAttribute("recommendCount")).isEqualTo(12L);
        assertThat(model.getAttribute("commentCount")).isEqualTo(3L);
        Mockito.verify(placeComments).countForPlace(42L, null);
    }

    /** 6 张照片 → 4 格缩略，末格「+3」（与稿子 A9 一致）；1 张 → 不列缩略（已在 hero）。 */
    @Test
    void thumbsShowFourSlotsWithRemainingCountOnTheLast() {
        List<PlacePhoto> six = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> PlacePhoto.fromMarking(42L, 7L, "https://cdn/" + i + ".jpg", i, true))
                .toList();
        when(photos.findVisible(anyLong(), anyBoolean(), any())).thenReturn(six);
        Model model = new ConcurrentModel();
        controller.placePage("tok", model, response);
        assertThat((List<?>) model.getAttribute("thumbs")).hasSize(4);
        assertThat(model.getAttribute("morePhotos")).isEqualTo(3);
        assertThat(model.getAttribute("hasThumbs")).isEqualTo(true);

        when(photos.findVisible(anyLong(), anyBoolean(), any())).thenReturn(six.subList(0, 4));
        Model four = new ConcurrentModel();
        controller.placePage("tok", four, response);
        assertThat(four.getAttribute("morePhotos")).isEqualTo(0);

        when(photos.findVisible(anyLong(), anyBoolean(), any())).thenReturn(six.subList(0, 1));
        Model one = new ConcurrentModel();
        controller.placePage("tok", one, response);
        assertThat(one.getAttribute("hasThumbs")).isEqualTo(false);
    }

    // ===== AC5 审核前不给图 =====

    /**
     * 🔴 **没有已过审的照片 → 不下发 og:image**（2026-09-15 拍板）。
     *
     * <p>社交平台会主动抓取并**缓存**预览图。审核放行前就给图，等于任何人都能造个场所、
     * 传张违规图、把链接甩进群 —— 等运营下架时，预览卡已经躺在几百个聊天记录里，
     * 而且不随下架消失。**H5 页面会变，预览卡不会。**
     */
    @Test
    void noOgImageWhileThePhotosAreStillUnderReview() {
        // findVisible(hasViewer=false) 只会回 VISIBLE 的行 —— 全都还挂着时它是空的。
        when(photos.findVisible(anyLong(), anyBoolean(), any())).thenReturn(List.of());

        Model model = new ConcurrentModel();
        controller.placePage("tok", model, response);

        assertThat(model.getAttribute("hasOgImage")).isEqualTo(false);
        assertThat(model.getAttribute("ogImage")).isNull();
        // 卡片仍有名称 / 邀约文案 / 地址 —— 只是没有大图。
        assertThat(model.getAttribute("ogTitle")).isEqualTo("Kopi Kayu Manis");
        assertThat((String) model.getAttribute("ogDescription")).contains("Jl. Senopati");
    }

    @Test
    void approvedPhotoBecomesTheOgImage() {
        PlacePhoto visible = PlacePhoto.fromMarking(42L, 7L, "https://cdn/a.jpg", 0, true);
        when(photos.findVisible(anyLong(), anyBoolean(), any())).thenReturn(List.of(visible));

        Model model = new ConcurrentModel();
        controller.placePage("tok", model, response);

        assertThat(model.getAttribute("hasOgImage")).isEqualTo(true);
        String og = (String) model.getAttribute("ogImage");
        assertThat(og)
                .as("E4：对外分发的公开桶图一律经服务端去 EXIF")
                .contains("x-oss-process=image/")
                .contains("format,jpg")
                .contains("resize,w_" + PlaceSharePageController.OG_IMAGE_WIDTH_PX);
    }

    /**
     * 🔴 **"对外可见"不等于"能当 og:image"**（code-review 2026-09-15）。
     *
     * <p>标记场所时那批走先发后审：三方 {@code RISKY}（有点像）/ {@code DEGRADED}
     * （压根没查成）**照样落 VISIBLE 对外展示**（Story 1.3 的产品口径，不改）。
     * 而 og:image 会被社交平台抓取并**缓存**，运营下架也撤不回来 ——
     * 那个场景下"没查成"必须当"不给图"，否则 AC5 挡的那个攻击原样成立：
     * 挑三方挂掉的时候造个场所、传张违规图、把链接甩进群。
     */
    @Test
    void aVisibleButNotCleanlyApprovedPhotoIsNotUsedAsOgImage() {
        // 三方 RISKY / DEGRADED 落库的那种：可见，但没有 og 资格。
        PlacePhoto shown = PlacePhoto.fromMarking(42L, 7L, "https://cdn/a.jpg", 0, false);
        when(photos.findVisible(anyLong(), anyBoolean(), any())).thenReturn(List.of(shown));

        Model model = new ConcurrentModel();
        controller.placePage("tok", model, response);

        assertThat(model.getAttribute("hasOgImage"))
                .as("🔴 只判 VISIBLE 的话，三方挂掉期间传的违规图会被永久缓存进各家聊天记录")
                .isEqualTo(false);
        // 但页面本身**照常展示**那张图 —— 展示与交给平台缓存是两件事。
        assertThat((List<?>) model.getAttribute("images")).hasSize(1);
    }

    /** 混着来时取**第一张合格的**，而不是"第一张可见的"。 */
    @Test
    void theFirstCleanlyApprovedPhotoWins() {
        PlacePhoto notClean = PlacePhoto.fromMarking(42L, 7L, "https://cdn/a.jpg", 0, false);
        PlacePhoto clean = PlacePhoto.fromMarking(42L, 7L, "https://cdn/b.jpg", 1, true);
        when(photos.findVisible(anyLong(), anyBoolean(), any()))
                .thenReturn(List.of(notClean, clean));

        Model model = new ConcurrentModel();
        controller.placePage("tok", model, response);

        assertThat((String) model.getAttribute("ogImage")).contains("b.jpg");
    }

    /**
     * 🔴 H5 是给全世界看的页面，**没有"上传者自视豁免"那回事**：
     * 取照片时必须传 `hasViewer=false` / `viewerId=null`，否则挂起中的图会被登录态带出去。
     */
    @Test
    void theSharePageNeverAsksForAViewerScopedPhotoList() {
        controller.placePage("tok", new ConcurrentModel(), response);

        Mockito.verify(photos).findVisible(42L, false, null);
    }

    // ===== AC6 深链 =====

    @Test
    void ctaDeepLinkUsesThePublicTokenNotTheName() {
        Model model = new ConcurrentModel();
        controller.placePage("tok", model, response);

        assertThat(model.getAttribute("deeplink")).isEqualTo("tailtopia://place/tok");
    }

    // ===== AC4 OG 例外的理由必须留在代码里 =====

    /**
     * 🔴 AC4 逐字要求「该理由**写进代码注释**，防被后人当漏做"修正"掉」。
     *
     * <p>所以这条测试断言的就是**注释还在**：名片页 / 里程碑页刻意不放 og:image，
     * 本页放 —— 一个只看见"别的分享页都没有"的人，很容易把这里的 og:image 当成疏漏删掉。
     */
    @Test
    void theOgImageExceptionIsExplainedInTheSourceAndTemplate() throws Exception {
        String controllerSrc = Files.readString(Path.of(
                "src/main/java/com/tailtopia/place/web/PlaceSharePageController.java"));
        String template = Files.readString(Path.of(
                "src/main/resources/templates/place_share.html"));

        for (String src : List.of(controllerSrc, template)) {
            assertThat(src)
                    .as("AC4：OG 例外的理由必须写在代码里")
                    .contains("有意例外");
            assertThat(src)
                    .as("AC5：审核前不给图的理由（预览卡会被缓存、不随下架消失）也要写在代码里")
                    .contains("缓存");
        }
    }

    /**
     * 🔴 H5 模板里的 CTA 必须真的把那个深链带出去（AC6）。
     *
     * <p>host 的另一半（App 认不认得、安卓清单有没有声明）在 App 仓里各有一条测试守。
     * ⚠️ **两侧各钉同一个字面量**，而不是让一侧去读另一侧的文件：
     * 两个仓的 CI 各有各的 `paths:` 过滤 —— 跨仓读文件的那条测试在对侧的 PR 上根本不会跑
     * （code-review 2026-09-15）。
     */
    @Test
    void theTemplateCarriesTheDeepLinkToTheCta() throws Exception {
        String template = Files.readString(Path.of(
                "src/main/resources/templates/place_share.html"));
        assertThat(template).contains("data-deeplink=${deeplink}");
    }

    /** 模板必须 noindex（防被搜索引擎收录后按 token 枚举）。 */
    @Test
    void theTemplateIsNoindex() throws Exception {
        String template = Files.readString(Path.of(
                "src/main/resources/templates/place_share.html"));
        assertThat(template).contains("noindex,nofollow");
    }

    /**
     * 对齐决策 D4：被合并的场所 → 301 到保留场所的分享页（旧链接继续有效，预览抓取也跟着跳）。
     * {@code resolveForView} 返回的是**另一个** token 的场所，控制器据此判断要不要跳。
     */
    @Test
    void mergedPlaceRedirectsPermanentlyToTheKeptOne() {
        Place keep = withId(Place.mark("keeptok", "Kopi Baru", PlaceType.CAFE, List.of(PlaceTag.PET_MENU),
                -6.2, 106.8, "Jl. Baru", null, 7L, "Jakarta"), 43L);
        when(places.resolveForView("oldtok")).thenReturn(Optional.of(keep));

        Object result = controller.placePage("oldtok", new org.springframework.ui.ExtendedModelMap(), response);

        assertThat(result).isInstanceOf(org.springframework.web.servlet.view.RedirectView.class);
        var rv = (org.springframework.web.servlet.view.RedirectView) result;
        assertThat(rv.getUrl()).isEqualTo("/place/keeptok");
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(rv, "statusCode"))
                .isEqualTo(org.springframework.http.HttpStatus.MOVED_PERMANENTLY);
    }

    private static Place place() {
        return Place.mark("tok", "Kopi Kayu Manis", PlaceType.CAFE,
                List.of(PlaceTag.PETS_ALLOWED_INSIDE, PlaceTag.OUTDOOR_SEATING),
                -6.235, 106.81, "Jl. Senopati No.75", "Ada area outdoor", 7L, "Jakarta");
    }

    private static Place withId(Place p, long id) {
        try {
            var f = Place.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
            return p;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Place.id 字段名变了，改这里", e);
        }
    }
}
