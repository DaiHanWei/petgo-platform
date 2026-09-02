package com.tailtopia.admin.tagicon;

import com.tailtopia.content.domain.ImageBytesMeasurer;
import com.tailtopia.content.domain.ImageSize;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 标签图标上传（V1.1.6 Story 11.5）—— 用户标签（AB-12A）与内容装饰标签（AB-10C）共用。
 *
 * <h2>🔴 为什么单独一个服务而不是复用种子图片那个</h2>
 * 校验骨架照抄种子图片上传（格式白名单 / 从字节量宽高 / OSS 公开对象），
 * 但<b>四条规则全都不一样</b>：
 * <ul>
 *   <li>格式：图标<b>只收 PNG / WebP</b> —— 见下</li>
 *   <li>大小上限 512KB，不是内容照片那个 10MB</li>
 *   <li>有<b>最小</b>尺寸要求（72×72），内容照片没有</li>
 *   <li>必须近似 1:1，内容照片允许各种比例</li>
 * </ul>
 * 硬塞进同一个服务会变成一堆 if(用途)，那种代码后面必然被改错。
 *
 * <h2>🔴 拒绝 JPEG，而且报错里必须写明理由</h2>
 * 图标叠在装饰标签的<b>橙红渐变胶囊</b>上（UI 稿 {@code .deco-badge}）、以及用户标签的昵称旁。
 * JPEG 不支持透明通道 ⇒ 传上来会是一个<b>白方块</b>贴在渐变上。
 * ⚠️ 只说"格式不支持"运营会换个 JPG 再试一次；把「不支持透明底」写进去，他才知道要去导 PNG。
 */
@Service
public class AdminTagIconService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AdminTagIconService.class);

    /**
     * 最小边长（2026-09-02 放宽：72 → 42）。用户标签整图按 14 逻辑像素显示，
     * 3 倍屏需要 42 物理像素 —— 这是"不糊"的下限，不是设计要求。
     *
     * <p>🔴 2026-09-02 产品定：**不再限制最大边长、不再要求正方形** ——
     * 运营没办法精确把图卡在某个尺寸上，而端上本来就是等比缩放，
     * 大图/长图都能正确显示。唯一保留的硬约束是格式（透明能力）与文件大小。
     */
    public static final int MIN_SIDE = 42;

    /** 单文件上限 512KB。🛡 <b>刻意不沿用</b>种子图片的 10MB —— 那是给内容照片定的。 */
    public static final long MAX_BYTES = 512L * 1024;

    /** 🛡 白名单，且<b>不含 JPEG</b>（透明底，见类注释）。 */
    private static final Set<String> ALLOWED = Set.of("image/png", "image/webp");

    /** OSS 子目录。 */
    private static final String FOLDER = "tag-icon";

    private final AliyunOssClient oss;
    private final MediaProperties mediaProps;
    private final MessageSource messages;

    public AdminTagIconService(AliyunOssClient oss, MediaProperties mediaProps,
            MessageSource messages) {
        this.oss = oss;
        this.mediaProps = mediaProps;
        this.messages = messages;
    }

    /**
     * 校验并上传，返回公开 URL。
     *
     * @param file 上传的图标；🛡 <b>空文件返回 {@code null}</b>（表示"这次不改图标"），不报错 ——
     *             编辑标签时运营常常只改名称，表单里那个 file input 自然是空的。
     */
    public String uploadOrKeep(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        String contentType = normalizedType(file);
        if (file.getSize() > MAX_BYTES) {
            throw AppException.validation(msg("admin.tagicon.tooLarge",
                    new Object[] {MAX_BYTES / 1024}));
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw AppException.validation(msg("admin.tagicon.readFailed", null));
        }

        // 🔴 从字节量，不传上去再回读（沿用 12-2 的做法：本地量快一个量级，也不受 CDN 未生效影响）。
        ImageSize size = ImageBytesMeasurer.measure(bytes);
        if (size == null) {
            // ⚠️ 与种子图片那条链路不同：那边"测不出来"是放过（Feed 有占位兜底），
            //    图标这边**必须拦住** —— 尺寸与比例是本 story 的核心校验，测不出来就等于没校验。
            throw AppException.validation(msg("admin.tagicon.unmeasurable", null));
        }
        requireSize(size);

        String key = mediaProps.getOss().normalizedKeyPrefix()
                + "public/" + FOLDER + "/" + UUID.randomUUID() + "." + extOf(contentType);
        try {
            return oss.putPublicObject(key, bytes, contentType);
        } catch (RuntimeException e) {
            // 🔴 上传是**外部依赖**：凭证缺失、对象存储不可用、网络超时都会抛，而且抛的不是
            //    AppException ⇒ 控制器那层的 catch 接不住 ⇒ 运营看到一个 500 白页。
            //    校验错误已经给了人话，唯独"传不上去"给 500 是最难排查的一种（运营只会说"存不了"）。
            // ⚠️ 报错里**不带异常细节**：桶名 / endpoint / 签名信息不该出现在运营界面上。
            log.warn("标签图标上传失败 key={} cls={} msg={}", key, e.getClass().getSimpleName(),
                    e.getMessage());
            throw AppException.validation(msg("admin.tagicon.uploadFailed", null));
        }
    }

    /**
     * 尺寸校验（2026-09-02 起只剩最小边长）。🛡 报错带**实际数值**，否则运营不知道要改成多少。
     * 上限与正方形要求已取消 —— 端上等比缩放，图什么形状都能正确显示。
     */
    private void requireSize(ImageSize size) {
        int w = size.w();
        int h = size.h();
        if (w < MIN_SIDE || h < MIN_SIDE) {
            throw AppException.validation(msg("admin.tagicon.tooSmall",
                    new Object[] {w, h, MIN_SIDE}));
        }
    }

    private String normalizedType(MultipartFile file) {
        String name = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String ct = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        // 🔴 JPEG 单独给一句话，别混进"格式不支持"里（见类注释）。
        if (ct.contains("jpeg") || ct.contains("jpg")
                || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            throw AppException.validation(msg("admin.tagicon.noJpeg", null));
        }
        if (ct.contains("heic") || ct.contains("heif")
                || name.endsWith(".heic") || name.endsWith(".heif")) {
            throw AppException.validation(msg("admin.tagicon.heic", null));
        }
        if (!ALLOWED.contains(ct)) {
            throw AppException.validation(msg("admin.tagicon.badType", null));
        }
        return ct;
    }

    private static String extOf(String contentType) {
        return "image/webp".equals(contentType) ? "webp" : "png";
    }

    /** 新建标签时缺图标的报错文案（图标是胶囊的固定组成部分，不是可选装饰）。 */
    public String iconRequiredMessage() {
        return msg("admin.tagicon.required", null);
    }

    /**
     * 供界面展示的尺寸规范说明（AC3：后台必须常驻这段文字）。
     *
     * <p>🔴 **两页给两段不同的话**（bug 20260828）：技术校验相同（正方形 / 72–1024 / ≤512KB），
     * 但两处图标的**最终显示尺寸与衬底完全不同** ——
     * 内容标签是 9px、叠在橙红渐变胶囊上；用户标签是金色圆底里的 8px。
     * 共用一段话的结果是：那段话只能讲技术下限，讲不了「你做的图最后长什么样」，
     * 于是运营做了 512×512 的精细图标，实机上是指甲盖上的一粒点，
     * 并据此认为「这个尺寸肯定不对」。
     *
     * @param context {@code contentTag} 或 {@code userTag}
     */
    public String specText(String context) {
        // {0}=最小边长 {1}=文件上限 KB（2026-09-02 起没有边长上限与正方形要求）。
        return msg("admin.tagicon.spec." + context,
                new Object[] {MIN_SIDE, MAX_BYTES / 1024});
    }

    private String msg(String key, Object[] args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
