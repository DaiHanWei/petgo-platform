package com.tailtopia.shared.media;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.dto.UploadUrlResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
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
 */
@Service
public class PresignedUploadService {

    private static final SecureRandom RNG = new SecureRandom();

    private final AliyunOssClient ossClient;
    private final MediaProperties props;

    public PresignedUploadService(AliyunOssClient ossClient, MediaProperties props) {
        this.ossClient = ossClient;
        this.props = props;
    }

    /**
     * 为当前用户签发收窄到目标桶 + 用户前缀的预签名上传票据。
     *
     * @param scope       PUBLIC（公开域）/ PRIVATE（私密域）
     * @param userId      当前 JWT 用户 id（前缀隔离）
     * @param contentType 拟上传 MIME（计入预签名；空则回退 {@code application/octet-stream}）
     */
    public UploadUrlResponse issue(MediaScope scope, long userId, String contentType) {
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

        String ct = blankToNull(contentType) == null ? "application/octet-stream" : contentType;
        String objectKey = props.getOss().normalizedKeyPrefix()
                + scope.prefix() + "/" + userId + "/" + randomToken() + "." + extFor(ct);
        String uploadUrl = ossClient.presignedPutUrl(
                bucket, objectKey, ct, props.getSignedUrl().getUploadTtlSeconds(), isPublic);

        // 客户端 PUT 必须原样携带这些头（Content-Type 已签入；公开域还须带 public-read ACL）。
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", ct);
        if (isPublic) {
            headers.put("x-oss-object-acl", "public-read");
        }
        String publicUrl = isPublic ? cdnBase + "/" + objectKey : null;
        return new UploadUrlResponse(uploadUrl, objectKey, "PUT", headers, publicUrl);
    }

    private String bucketFor(MediaScope scope) {
        return switch (scope) {
            case PUBLIC -> props.getOss().getPublicBucket();
            case PRIVATE -> props.getOss().getPrivateBucket();
        };
    }

    /** 16 字节随机 → URL-safe base64，无填充。对外标识不可枚举（架构 §Enforcement）。 */
    private static String randomToken() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String extFor(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/heic" -> "heic";
            default -> "bin";
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
