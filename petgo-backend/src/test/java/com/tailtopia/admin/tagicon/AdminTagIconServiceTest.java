package com.tailtopia.admin.tagicon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockMultipartFile;

/**
 * L0：标签图标上传校验（Story 11.5 · AC2）。
 *
 * <p>🔴 本类的价值全在**报错要能照着改**：只说"格式不对 / 尺寸不对"的话，
 * 运营会反复重试同一张图。所以每条都断言报错里带了**具体数字或具体理由**。
 */
class AdminTagIconServiceTest {

    private AliyunOssClient oss;
    private AdminTagIconService service;

    @BeforeEach
    void setUp() {
        oss = mock(AliyunOssClient.class);
        when(oss.putPublicObject(anyString(), any(), anyString()))
                .thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));

        MediaProperties props = mock(MediaProperties.class);
        MediaProperties.Oss ossProps = mock(MediaProperties.Oss.class);
        when(props.getOss()).thenReturn(ossProps);
        when(ossProps.normalizedKeyPrefix()).thenReturn("petgo/");

        // 报错文案直接回 key + 参数，便于断言"带了哪些数字"。
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(), any())).thenAnswer(inv -> {
            Object[] args = inv.getArgument(1);
            StringBuilder sb = new StringBuilder((String) inv.getArgument(0));
            if (args != null) {
                for (Object a : args) {
                    sb.append('|').append(a);
                }
            }
            return sb.toString();
        });

        service = new AdminTagIconService(oss, props, messages);
    }

    /** 造一张指定尺寸的 PNG。 */
    private static byte[] png(int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockMultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("iconFile", name, type, bytes);
    }

    // ── 空文件 = 不改图标 ────────────────────────────────────────────

    /**
     * 🛡 编辑标签时运营常常只改名称，那个 file input 自然是空的。
     * 空文件必须表示「这次不改图标」而**不是报错**，也不是「把图标清空」。
     */
    @Test
    void emptyFileMeansKeepCurrentIcon() {
        assertThat(service.uploadOrKeep(null)).isNull();
        assertThat(service.uploadOrKeep(file("x.png", "image/png", new byte[0]))).isNull();
        verify(oss, never()).putPublicObject(anyString(), any(), anyString());
    }

    // ── AC2 格式 ────────────────────────────────────────────────────

    /**
     * 🔴 JPEG 要有**自己那句话**，不能混进「格式不支持」。
     *
     * <p>图标叠在橙红渐变胶囊上，JPEG 存不了透明 ⇒ 会是个白方块。
     * 只说"格式不支持"运营会换张 JPG 再试一次。
     */
    @Test
    void jpegIsRejectedWithTheTransparencyReason() {
        assertThatThrownBy(() -> service.uploadOrKeep(
                file("icon.jpg", "image/jpeg", png(128, 128))))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("noJpeg");
    }

    /** 文件名是 .jpg 但 content-type 撒谎 → 同样拒（沿用 12-2 的双重判断）。 */
    @Test
    void jpegByFilenameIsAlsoRejected() {
        assertThatThrownBy(() -> service.uploadOrKeep(
                file("icon.JPEG", "application/octet-stream", png(128, 128))))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("noJpeg");
    }

    @Test
    void heicAndOtherTypesAreRejectedSeparately() {
        assertThatThrownBy(() -> service.uploadOrKeep(
                file("a.heic", "image/heic", png(128, 128))))
                .hasMessageContaining("heic");
        assertThatThrownBy(() -> service.uploadOrKeep(
                file("a.gif", "image/gif", png(128, 128))))
                .hasMessageContaining("badType");
    }

    @Test
    void pngAndWebpAreAccepted() {
        assertThat(service.uploadOrKeep(file("a.png", "image/png", png(128, 128))))
                .startsWith("https://cdn.example/petgo/public/tag-icon/");
    }

    // ── 尺寸（2026-09-02 放宽：只剩最小边长，上限与正方形要求取消）──────────

    /** 🔴 差 1 像素也拒 —— 边界必须是硬的，否则「≥42」这条规格没有意义。 */
    @Test
    void oneShortSidePixelBelowMinimumIsRejected() {
        assertThatThrownBy(() -> service.uploadOrKeep(file("a.png", "image/png", png(41, 42))))
                .hasMessageContaining("tooSmall")
                .hasMessageContaining("41")   // 报错要带实际尺寸
                .hasMessageContaining("42");  // 和要求的最小值
        assertThat(service.uploadOrKeep(file("a.png", "image/png", png(42, 42)))).isNotNull();
    }

    /**
     * 2026-09-02 产品定：大图、长图、任意比例一律放行 —— 运营没办法精确卡尺寸，
     * 端上等比缩放什么形状都能正确显示。这三张图在旧规则下全都会被拒。
     */
    @Test
    void anyAspectRatioAndLargeSizesAreAccepted() {
        assertThat(service.uploadOrKeep(file("a.png", "image/png", png(1200, 1200)))).isNotNull();
        assertThat(service.uploadOrKeep(file("a.png", "image/png", png(72, 90)))).isNotNull();
        assertThat(service.uploadOrKeep(file("a.png", "image/png", png(300, 100)))).isNotNull();
    }

    // ── AC2 文件大小 ────────────────────────────────────────────────

    /**
     * 🛡 上限是 512KB，**不是**种子图片那个 10MB。
     *
     * <p>那个数是给内容照片定的；图标沿用它等于没有上限。
     */
    @Test
    void sizeCapIsHalfAMegabyteNotTenMegabytes() {
        assertThat(AdminTagIconService.MAX_BYTES).isEqualTo(512L * 1024);
        byte[] big = new byte[600 * 1024];
        assertThatThrownBy(() -> service.uploadOrKeep(file("a.png", "image/png", big)))
                .hasMessageContaining("tooLarge")
                .hasMessageContaining("512");
    }

    // ── 量不出尺寸时的取舍 ──────────────────────────────────────────

    /**
     * 🔴 与种子图片那条链路**相反**：那边"量不出来"是放过（Feed 有占位兜底），
     * 图标这边必须拦住 —— 尺寸与比例校验是本 story 的核心，量不出来就等于没校验。
     */
    @Test
    void unmeasurableFileIsRejectedUnlikeTheSeedImagePath() {
        byte[] notAnImage = "this is not a png".getBytes();
        assertThatThrownBy(() -> service.uploadOrKeep(file("a.png", "image/png", notAnImage)))
                .hasMessageContaining("unmeasurable");
        verify(oss, never()).putPublicObject(anyString(), any(), anyString());
    }

    // ── 界面文案 ────────────────────────────────────────────────────

    /** AC3：尺寸规范文案要带两个数（最小边 / 大小上限），**两页各一份**（2026-09-02 起无上限边长）。 */
    @Test
    void specTextCarriesBothNumbers() {
        for (String ctx : new String[] {"contentTag", "userTag"}) {
            assertThat(service.specText(ctx))
                    .as(ctx + " 的规格文案")
                    .contains("42").contains("512");
        }
    }

    /**
     * 🔴 **两页的规格文案必须不一样**（bug 20260828）。
     *
     * <p>技术校验相同，但两处图标的最终显示尺寸与衬底完全不同（内容标签 9px 叠渐变胶囊 /
     * 用户标签 8px 叠金色圆底）。共用一段话时它只能讲技术下限、讲不了「你做的图最后长什么样」——
     * 运营因此做了 512×512 的精细图标，实机上是指甲盖上的一粒点，并据此认为尺寸规格是错的。
     *
     * <p>⚠️ 断"两段不相同"而不是断具体措辞：措辞会改，而**共用同一段**才是那个错误。
     */
    @Test
    void theTwoPagesGetDifferentSpecText() {
        assertThat(service.specText("contentTag"))
                .as("🔴 两页又共用同一段规格文案了 —— 它讲不出各自的实际显示尺寸")
                .isNotEqualTo(service.specText("userTag"));
    }

    // ── V1.4.0 gift Story 3.1 · AC5：礼物用途复用同一套校验，只换目录与文案 ─────────────

    /** 礼物图标进自己的 OSS 子目录 {@code public/gift-icon/}，不和标签图标混放。 */
    @Test
    void giftPurposeUploadsUnderGiftIconFolder() {
        assertThat(service.uploadOrKeep(file("a.png", "image/png", png(112, 112)), IconPurpose.GIFT))
                .startsWith("https://cdn.example/petgo/public/gift-icon/");
        assertThat(service.uploadOrKeep(file("a.webp", "image/webp", png(112, 112)), IconPurpose.TAG))
                .as("显式 TAG 与单参方法同目录")
                .startsWith("https://cdn.example/petgo/public/tag-icon/");
    }

    /**
     * 真实 WebP 文件（不是改了类型的 PNG）能通过尺寸校验并上传，扩展名为 .webp；PRD AB-35 写明图标支持 PNG / WebP。
     * 🔴 此前 JDK ImageIO 读不了 WebP，真 WebP 一律被判「测不出尺寸」拒收，旧测试拿 PNG 字节冒充 WebP 没发现。
     */
    @Test
    void realWebpIconsAreAcceptedAndTooSmallStillRejected() throws Exception {
        for (String name : new String[] {"alpha-336x336.webp", "lossy-112x96.webp", "lossless-300x200.webp",
                "animated-64x48.webp"}) {
            assertThat(service.uploadOrKeep(file(name, "image/webp", webp(name)), IconPurpose.GIFT))
                    .as(name).startsWith("https://cdn.example/petgo/public/gift-icon/").endsWith(".webp");
        }
        assertThatThrownBy(() -> service.uploadOrKeep(file("s.webp", "image/webp", webp("alpha-40x40.webp")),
                IconPurpose.GIFT))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("admin.gifticon.tooSmall").hasMessageContaining("40");
    }

    private static byte[] webp(String name) throws java.io.IOException {
        try (var in = AdminTagIconServiceTest.class.getResourceAsStream("/fixtures/webp/" + name)) {
            return in.readAllBytes();
        }
    }

    /** 礼物版报错走 {@code admin.gifticon.*}：JPEG / 尺寸 / 大小都换成「礼物图标」文案，规则不变。 */
    @Test
    void giftPurposeReportsGiftWording() {
        assertThatThrownBy(() -> service.uploadOrKeep(file("icon.jpg", "image/jpeg", png(128, 128)), IconPurpose.GIFT))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("admin.gifticon.noJpeg");
        assertThatThrownBy(() -> service.uploadOrKeep(file("a.gif", "image/gif", png(128, 128)), IconPurpose.GIFT))
                .hasMessageContaining("admin.gifticon.badType");
        assertThatThrownBy(() -> service.uploadOrKeep(file("a.png", "image/png", png(41, 42)), IconPurpose.GIFT))
                .hasMessageContaining("admin.gifticon.tooSmall").hasMessageContaining("41");
        assertThatThrownBy(() -> service.uploadOrKeep(file("a.png", "image/png", new byte[600 * 1024]),
                IconPurpose.GIFT))
                .hasMessageContaining("admin.gifticon.tooLarge").hasMessageContaining("512");
        assertThat(service.uploadOrKeep(null, IconPurpose.GIFT)).as("空文件 = 保留原图标").isNull();
    }

    /** 礼物版「必须上传」与规格说明（新建 / 编辑两段，都带最小边与大小上限）；单参旧方法行为不变。 */
    @Test
    void giftPurposeTextsAndLegacyDefaultsAreUnchanged() {
        assertThat(service.iconRequiredMessage(IconPurpose.GIFT)).isEqualTo("admin.gifticon.required");
        assertThat(service.iconRequiredMessage()).isEqualTo("admin.tagicon.required");
        assertThat(service.specText(IconPurpose.GIFT, "create")).isEqualTo("admin.gifticon.spec.create|42|512");
        assertThat(service.specText(IconPurpose.GIFT, "edit")).isEqualTo("admin.gifticon.spec.edit|42|512");
        assertThat(service.specText("userTag")).isEqualTo("admin.tagicon.spec.userTag|42|512");
        assertThatThrownBy(() -> service.uploadOrKeep(file("icon.jpg", "image/jpeg", png(128, 128))))
                .hasMessageContaining("admin.tagicon.noJpeg");
    }
}
