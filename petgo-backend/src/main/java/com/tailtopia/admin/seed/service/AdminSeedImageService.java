package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.dto.UploadedImage;
import com.tailtopia.content.domain.ImageSize;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;
import java.awt.Dimension;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 后台单条发布的图片上传（V1.1.6 Story 12.2 · AC2/AC3 · AB-3J）。
 *
 * <p><b>此前后台只能填图片 URL</b> —— 运营为了发一条内容得先去别处传图、拿链接、再粘回来。
 * 本类把那一步收回来：直传对象存储，<b>复用兽医头像换图那条既有链路</b>
 * （{@link AliyunOssClient#putPublicObject}），不是新建基建。
 *
 * <h2>🛡 不支持 HEIC（A-12）</h2>
 * App 端支持 HEIC 是因为 iOS 相册直出；后台是<b>桌面浏览器上传</b>，HEIC 在桌面端兼容性差，
 * 而运营素材基本已经是 JPG/PNG。放开它只会让"上传成功但谁都打不开"的图进库。
 * ⚠️ 拒绝时要给<b>明确原因</b>，不能只说"格式不支持" —— 否则运营会反复重试同一张图。
 */
@Service
public class AdminSeedImageService {

    /** 与 App 端一致：单条 ≤9 张。 */
    public static final int MAX_IMAGES = 9;

    /** 单张 ≤10MB（AC2）。比兽医头像那条链路的 5MB 宽松 —— 内容图本来就更大。 */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    /** 🛡 白名单，不是黑名单：新格式默认拒绝，而不是默认放进来。 */
    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/webp");

    private final AliyunOssClient oss;
    private final MediaProperties mediaProps;
    private final MessageSource messages;

    public AdminSeedImageService(AliyunOssClient oss, MediaProperties mediaProps,
            MessageSource messages) {
        this.oss = oss;
        this.mediaProps = mediaProps;
        this.messages = messages;
    }

    /**
     * 校验 → 量原始宽高 → 上传 → 返回带裁切预判的结果。
     *
     * @param folder 对象存储下的子目录（如 {@code seed-post} / {@code virtual-avatar}）
     */
    public UploadedImage upload(MultipartFile file, String folder) {
        String contentType = normalizedType(file);
        if (file.getSize() > MAX_BYTES) {
            throw AppException.validation(msg("admin.seed.upload.tooLarge",
                    new Object[] {MAX_BYTES / 1024 / 1024}));
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw AppException.validation(msg("admin.seed.upload.readFailed", null));
        }

        // 🔴 从**字节**量宽高，不走网络：图还没上传，没有 URL 可读；
        //    而且本地量比"传上去再回来读一遍"快一个量级、也不会因 CDN 未生效而失败。
        ImageSize size = measure(bytes);
        ImageRatioAdvisor.Advice advice = ImageRatioAdvisor.advise(size);

        String key = mediaProps.getOss().normalizedKeyPrefix()
                + "public/" + folder + "/" + UUID.randomUUID() + "." + extOf(contentType);
        String url = oss.putPublicObject(key, bytes, contentType);

        return new UploadedImage(url, size == null ? 0 : size.w(), size == null ? 0 : size.h(),
                advice.warns() ? warningText(advice) : null);
    }

    /**
     * 裁切预判的文案（AC3）。
     *
     * <p>🛡 <b>必须带具体数字与方向</b>，不可笼统写"会被裁切" —— 后者运营看了也不知道要不要重裁。
     * 文案里同时给"共裁掉"和"每侧"两个数：这两个数差一倍，只给一个必然被读错。
     */
    private String warningText(ImageRatioAdvisor.Advice a) {
        String key = a.crop() == ImageRatioAdvisor.Crop.SIDES
                ? "admin.seed.upload.cropSides"
                : "admin.seed.upload.cropTopBottom";
        return msg(key, new Object[] {
                String.format(Locale.ROOT, "%.2f", a.ratio()),
                ImageRatioAdvisor.MIN_RATIO, ImageRatioAdvisor.MAX_RATIO,
                a.totalPercent(), a.perSidePercent()});
    }

    /**
     * 格式白名单。
     *
     * <p>⚠️ 浏览器对 HEIC 报的 content-type 有好几种（{@code image/heic} / {@code image/heif} /
     * 有时干脆是 {@code application/octet-stream}），所以<b>顺带看一眼文件名</b> ——
     * 目的不是严密识别，而是能对最常见的那种情况给出"HEIC 不支持"这句准话，
     * 而不是让运营对着"格式不支持"反复重试同一张图。
     */
    private String normalizedType(MultipartFile file) {
        String name = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String ct = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (file.isEmpty()) {
            throw AppException.validation(msg("admin.seed.upload.empty", null));
        }
        if (ct.contains("heic") || ct.contains("heif")
                || name.endsWith(".heic") || name.endsWith(".heif")) {
            throw AppException.validation(msg("admin.seed.upload.heic", null));
        }
        if (!ALLOWED.contains(ct)) {
            throw AppException.validation(msg("admin.seed.upload.badType", null));
        }
        return ct;
    }

    private static String extOf(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    /**
     * 只读文件头拿宽高（做法与 {@code ImageSizeBackfillService#measure} 同源）。
     *
     * <p>测不出来返回 {@code null} —— 🛡 <b>绝不因此拦住上传</b>：格式冷门与"这张图能不能用"
     * 是两件事，而 Feed 侧对无尺寸的图本来就有占位兜底。
     */
    static ImageSize measure(byte[] bytes) {
        ImageReader reader = null;
        try (InputStream in = new java.io.ByteArrayInputStream(bytes);
                ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            reader = readers.next();
            reader.setInput(iis, true, true);
            Dimension d = new Dimension(reader.getWidth(0), reader.getHeight(0));
            ImageSize size = new ImageSize(d.width, d.height);
            return size.isReasonable() ? size : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    private String msg(String key, Object[] args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
