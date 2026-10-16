package com.tailtopia.content.service;

import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.MediaType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.VideoPayload;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaObjectKeys;
import com.tailtopia.shared.media.MediaProperties;
import com.tailtopia.shared.media.MediaScope;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 视频帖发布校验（专题线 video Story 1.3 · AD-5 校验表 · FR-12）。
 *
 * <h2>🔴 本类刻意不加 {@code @Transactional}</h2>
 * {@link #verifyObjects} 会对 OSS 发 HEAD（一次外网往返），必须在<b>事务外</b>调用（一致性规则 7）——
 * 由 {@code ContentApiController} 在进入 {@code ContentService.publish} 之前调。
 * {@link #validateShape} 是纯函数，事务内外都可复用。
 *
 * <p>归属校验是<b>新写</b>的：现有图文发帖链路没有归属校验（V-20 维持不修），这里只给视频帖用。
 * 错误 detail 不回显用户传入的 key / URL。
 */
@Component
public class VideoPostValidator {

    static final int MIN_DURATION_MS = 3_000;
    static final int MAX_DURATION_MS = 90_000;
    static final int MAX_SHORT_SIDE = 720;
    static final String VIDEO_CONTENT_TYPE = "video/mp4";

    /** 事务外校验通过的视频（HEAD 取得的实际大小）。 */
    public record VerifiedVideo(String objectKey, long sizeBytes) {
    }

    private final VipLevelService vipLevelService;
    private final AliyunOssClient ossClient;
    private final MediaProperties props;

    public VideoPostValidator(VipLevelService vipLevelService, AliyunOssClient ossClient, MediaProperties props) {
        this.vipLevelService = vipLevelService;
        this.ossClient = ossClient;
        this.props = props;
    }

    /**
     * 本地即可判死的形状校验（纯函数）：混发 / 类型与附带字段 / 时长宽高。
     * {@code mediaType} 省略或 IMAGE 时只检查「没带 video」。
     */
    public void validateShape(ContentPostCreateRequest req) {
        if (req.mediaTypeOrImage() != MediaType.VIDEO) {
            if (req.video() != null) {
                throw AppException.videoMixedMedia("图文帖不能带视频");
            }
            return;
        }
        List<String> imageUrls = req.imageUrls();
        if (req.video() == null || imageUrls == null || imageUrls.size() != 1
                || imageUrls.get(0) == null || imageUrls.get(0).isBlank()) {
            throw AppException.videoMixedMedia("视频帖需要 1 个视频和 1 张封面，不能与图片混发");
        }
        if (req.type() != ContentType.DAILY && req.type() != ContentType.KNOWLEDGE) {
            throw AppException.validation("视频帖只能选日常或科普");
        }
        if (req.petId() != null || req.eventDate() != null
                || (req.placeCheckinToken() != null && !req.placeCheckinToken().isBlank())) {
            throw AppException.validation("视频帖不能关联宠物、打卡或日期");
        }
        if (req.imageCropOffsets() != null) {
            throw AppException.validation("视频帖不支持设置露出位置");
        }
        VideoPayload v = req.video();
        if (v.durationMs() == null || v.durationMs() < MIN_DURATION_MS || v.durationMs() > MAX_DURATION_MS) {
            throw AppException.videoObjectInvalid("视频时长需在 3–90 秒之间");
        }
        if (v.width() == null || v.height() == null || v.width() <= 0 || v.height() <= 0
                || Math.min(v.width(), v.height()) > MAX_SHORT_SIDE) {
            throw AppException.videoObjectInvalid("视频尺寸不符合要求");
        }
    }

    /**
     * 事务外完整校验（顺序刻意：本地能判死的先判，HEAD 放最后）：
     * 等级 → 形状 → 视频 key 归属 → 封面归属 → HEAD 视频对象 → 封面存在且私有。
     *
     * @return 通过校验的视频（含实际大小），交给事务内的 {@code ContentService.publish}
     */
    public VerifiedVideo verifyObjects(long userId, ContentPostCreateRequest req) {
        if (!vipLevelService.canPublishVideo(userId)) {
            throw AppException.videoPermissionDenied("当前账号不能发布视频");
        }
        validateShape(req);
        if (req.mediaTypeOrImage() != MediaType.VIDEO) {
            throw AppException.videoMixedMedia("不是视频帖");
        }
        String key = req.video().objectKey();
        if (!ownsVideoKey(userId, key)) {
            throw AppException.videoObjectInvalid("视频无效，请重新上传");
        }
        String coverKey = ossClient.objectKeyOfPublicUrl(req.imageUrls().get(0)).orElse(null);
        if (!MediaObjectKeys.belongsTo(props, MediaScope.PUBLIC, userId, coverKey)) {
            throw AppException.videoObjectInvalid("封面无效，请重新上传");
        }
        AliyunOssClient.ObjectHead head = ossClient.headObject(props.getOss().getPublicBucket(), key)
                .orElseThrow(() -> AppException.videoObjectInvalid("视频还没有上传完成，请重新上传"));
        String ct = head.contentType() == null ? "" : head.contentType().strip().toLowerCase(Locale.ROOT);
        if (!VIDEO_CONTENT_TYPE.equals(ct) || head.contentLength() <= 0
                || head.contentLength() > props.getVideo().getMaxBytes()) {
            throw AppException.videoObjectInvalid("视频文件不符合要求，请重新上传");
        }
        // code review：封面必须存在且是 VIDEO_COVER 签出的私有对象。已公开的图（头像、图文图片、
        // 已发布视频帖的封面）不能拿来当封面 —— 对账器按帖子改封面 ACL，共用会互相把对方改私有。
        boolean coverPrivate = ossClient.isObjectPrivate(props.getOss().getPublicBucket(), coverKey)
                .orElseThrow(() -> AppException.videoObjectInvalid("封面还没有上传完成，请重新上传"));
        if (!coverPrivate) {
            throw AppException.videoObjectInvalid("封面无效，请重新上传");
        }
        return new VerifiedVideo(key, head.contentLength());
    }

    /** 视频 key 必须是 1.2 签发的形态：{@code <keyPrefix>video/<userId>/<随机>.mp4}。 */
    boolean ownsVideoKey(long userId, String key) {
        if (key == null || key.isBlank() || key.contains("..") || !key.endsWith(".mp4")) {
            return false;
        }
        String prefix = props.getOss().normalizedKeyPrefix() + "video/" + userId + "/";
        return key.startsWith(prefix) && key.length() > prefix.length() + ".mp4".length()
                && key.indexOf('/', prefix.length()) < 0;
    }
}
