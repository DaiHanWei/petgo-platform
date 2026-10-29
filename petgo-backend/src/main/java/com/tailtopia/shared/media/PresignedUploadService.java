package com.tailtopia.shared.media;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.dto.UploadUrlResponse;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 预签名上传票据签发（单桶 + 对象级 ACL）。
 *
 * <p>后端用 env 注入的 AccessKey 现签一张「限定对象 + 限定头 + 短 TTL」的预签名 PUT URL 给客户端，
 * 客户端凭此**直传 OSS（字节不绕后端）**，而**真 AccessKey 始终只在后端**——等价于 STS 直传的安全姿态，
 * 但不需要 RAM 角色/STS。对象 key 服务端生成、不可枚举、收窄在 {@code <scope>/<userId>/} 前缀下。
 *
 * <ul>
 *   <li>{@link MediaScope#PUBLIC}：签入 {@code x-oss-object-acl:public-read}，落桶即公网可读；返回 publicUrl。</li>
 *   <li>{@link MediaScope#PRIVATE}：仅签 PUT，对象私有；读取须经 {@link SignedUrlService}，publicUrl=null。</li>
 * </ul>
 *
 * <h2>加固（专题线 video Story 1.2 · FR-23 / AD-4）</h2>
 * <ul>
 *   <li>类型白名单只有图片（{@link #ALLOWED_CONTENT_TYPES}）：此前空类型回落 {@code application/octet-stream}、
 *       任意类型落成 {@code .bin}，整条链路等于一块免费网盘。视频只能走 {@link VideoUploadPolicyService}。</li>
 *   <li>一律签入 {@code x-oss-forbid-overwrite: true} 并放进响应 headers（旧版 App 原样回带 headers，不受影响）。</li>
 *   <li>{@link UploadPurpose#VIDEO_COVER}：公开域前缀 + {@code private} ACL；等级判断在 Controller 做。</li>
 * </ul>
 */
@Service
public class PresignedUploadService {

    /** 通用上传只接受的图片类型 → 扩展名。 */
    static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/heic", "heic");

    /** 视频封面固定 JPEG（FR-8）。 */
    static final String VIDEO_COVER_CONTENT_TYPE = "image/jpeg";

    private final AliyunOssClient ossClient;
    private final MediaProperties props;

    public PresignedUploadService(AliyunOssClient ossClient, MediaProperties props) {
        this.ossClient = ossClient;
        this.props = props;
    }

    /** 不带用途的普通图片上传。 */
    public UploadUrlResponse issue(MediaScope scope, long userId, String contentType) {
        return issue(scope, userId, contentType, null);
    }

    /**
     * 为当前用户签发收窄到目标桶 + 用户前缀的预签名上传票据。
     *
     * @param scope       PUBLIC（公开域）/ PRIVATE（私密域）
     * @param userId      当前 JWT 用户 id（前缀隔离）
     * @param contentType 拟上传 MIME，必须在图片白名单内（计入预签名）
     * @param purpose     可空；{@link UploadPurpose#VIDEO_COVER} 时签成 private（调用方负责等级判断）
     */
    public UploadUrlResponse issue(MediaScope scope, long userId, String contentType, UploadPurpose purpose) {
        // 白名单在任何 OSS 调用之前；不回显用户传入的原串。
        String ct = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        String ext = ALLOWED_CONTENT_TYPES.get(ct);
        if (ext == null) {
            throw AppException.uploadContentTypeNotAllowed("只能上传图片");
        }
        boolean videoCover = purpose == UploadPurpose.VIDEO_COVER;
        if (videoCover) {
            if (scope != MediaScope.PUBLIC) {
                throw AppException.validation("视频封面只能上传到公开域");
            }
            if (!VIDEO_COVER_CONTENT_TYPE.equals(ct)) {
                throw AppException.uploadContentTypeNotAllowed("视频封面只能是 JPEG");
            }
        }

        String bucket = bucketFor(scope);
        if (bucket == null || bucket.isBlank()) {
            throw AppException.mediaCredential("媒体存储未配置，请联系运营");
        }
        boolean isPublic = scope == MediaScope.PUBLIC;
        String cdnBase = blankToNull(props.getOss().getCdnBaseUrl());
        // 公开域必须能公网读：漏配 CDN/公网 base 即 fail-fast，杜绝对象落桶却不可访问。
        if (isPublic && cdnBase == null) {
            throw AppException.mediaCredential("媒体存储未配置，请联系运营");
        }

        String objectKey = props.getOss().normalizedKeyPrefix()
                + scope.prefix() + "/" + userId + "/" + MediaObjectKeys.randomToken() + "." + ext;
        // 视频封面：公开域前缀（发帖时按前缀校验归属），但对象先私有，发布后由对账器改公开。
        String acl = videoCover ? "private" : (isPublic ? "public-read" : null);
        String uploadUrl = ossClient.presignedPutUrl(
                bucket, objectKey, ct, props.getSignedUrl().getUploadTtlSeconds(), acl);

        // 客户端 PUT 必须原样携带这些头（全部已签入，漏发即 SignatureDoesNotMatch）。
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", ct);
        if (acl != null) {
            headers.put("x-oss-object-acl", acl);
        }
        headers.put(AliyunOssClient.FORBID_OVERWRITE_HEADER, "true");
        String publicUrl = isPublic ? cdnBase + "/" + objectKey : null;
        return new UploadUrlResponse(uploadUrl, objectKey, "PUT", headers, publicUrl);
    }

    private String bucketFor(MediaScope scope) {
        return switch (scope) {
            case PUBLIC -> props.getOss().getPublicBucket();
            case PRIVATE -> props.getOss().getPrivateBucket();
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
