package com.tailtopia.shared.media.web;

import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.PresignedUploadService;
import com.tailtopia.shared.media.UploadPurpose;
import com.tailtopia.shared.media.VideoUploadPolicyService;
import com.tailtopia.shared.media.dto.UploadUrlRequest;
import com.tailtopia.shared.media.dto.UploadUrlResponse;
import com.tailtopia.shared.media.dto.VideoUploadPolicyResponse;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 媒体基建预签名上传端点（Story 2.1 · AC1）。跨模块共享基础设施，置于 {@code shared/media}。
 *
 * <p>{@code POST /api/v1/media/upload-url}：需 JWT；按 scope 签发限定对象 + 限定头 + 短 TTL 的
 * 预签名上传票据（真 AccessKey 始终只在后端）。套写端点限流防滥发。
 * 护栏：预签名 URL 含签名但**绝不进 INFO 日志**。
 *
 * <p>{@code POST /api/v1/media/video-upload-policy}（专题线 video Story 1.2 · AD-3）：会员等级 ≥ 10 才签发视频
 * PostObject 策略；先判等级再计每日配额（等级不足不计数）。{@code purpose=VIDEO_COVER} 的封面上传同样先判等级。
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private static final int RATE_LIMIT = 30;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final PresignedUploadService uploadService;
    private final RedisRateLimiter rateLimiter;
    private final VipLevelService vipLevelService;
    private final VideoUploadPolicyService videoUploadPolicyService;

    public MediaController(PresignedUploadService uploadService, RedisRateLimiter rateLimiter,
            VipLevelService vipLevelService, VideoUploadPolicyService videoUploadPolicyService) {
        this.uploadService = uploadService;
        this.rateLimiter = rateLimiter;
        this.vipLevelService = vipLevelService;
        this.videoUploadPolicyService = videoUploadPolicyService;
    }

    @PostMapping("/upload-url")
    public UploadUrlResponse issueUploadUrl(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UploadUrlRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:media:upload:" + userId, RATE_LIMIT, RATE_WINDOW);
        if (req.purpose() == UploadPurpose.VIDEO_COVER) {
            requireVideoPermission(userId);
        }
        return uploadService.issue(req.scope(), userId, req.contentType(), req.purpose());
    }

    @PostMapping("/video-upload-policy")
    public VideoUploadPolicyResponse issueVideoUploadPolicy(@AuthenticationPrincipal Jwt jwt) {
        long userId = currentUserId(jwt);
        requireVideoPermission(userId);
        return videoUploadPolicyService.issue(userId);
    }

    private void requireVideoPermission(long userId) {
        if (!vipLevelService.canPublishVideo(userId)) {
            throw AppException.videoPermissionDenied("当前账号不能发布视频");
        }
    }

    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}
