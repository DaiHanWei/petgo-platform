package com.tailtopia.shared.media;

import com.aliyun.oss.model.MatchMode;
import com.aliyun.oss.model.PolicyConditions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.dto.VideoUploadPolicyResponse;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 视频 PostObject 上传策略签发（专题线 video Story 1.2 · AD-3 · FR-11 / FR-23）。
 *
 * <p>视频不走预签名 PUT：PUT 签名限不住大小。这里签一份 PostObject 策略，由 OSS 在服务端强制：
 * 完整 key（eq，不是前缀）、1B–{@code maxBytes}、{@code video/mp4}、{@code private}、禁止覆盖、
 * {@code success_action_status=200}。对象先私有，发布后由对账器（Story 1.4）改公开。
 *
 * <p>每账号每 WIB 自然日限 {@code dailyPolicyLimit} 次（Redis 计数）。<b>等级判断不在这里</b>，
 * 由 Controller 先判 —— 等级不足的请求不计数。
 *
 * <p>签名本地计算，不发网络请求（一致性规则 7 不涉及）。policy / signature 不进日志。
 */
@Service
public class VideoUploadPolicyService {

    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    static final String VIDEO_CONTENT_TYPE = "video/mp4";
    static final String VIDEO_PREFIX = "video";
    /** 计数键 TTL：覆盖一个 WIB 自然日再留余量。 */
    static final Duration QUOTA_TTL = Duration.ofHours(48);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final AliyunOssClient ossClient;
    private final MediaProperties props;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public VideoUploadPolicyService(AliyunOssClient ossClient, MediaProperties props, StringRedisTemplate redis) {
        this(ossClient, props, redis, Clock.systemUTC());
    }

    VideoUploadPolicyService(AliyunOssClient ossClient, MediaProperties props, StringRedisTemplate redis,
            Clock clock) {
        this.ossClient = ossClient;
        this.props = props;
        this.redis = redis;
        this.clock = clock;
    }

    /** 为 {@code userId} 签发一份视频上传策略（调用方已确认等级 ≥ 10）。 */
    public VideoUploadPolicyResponse issue(long userId) {
        String bucket = props.getOss().getPublicBucket();
        if (bucket == null || bucket.isBlank()) {
            throw AppException.mediaCredential("媒体存储未配置，请联系运营");
        }
        consumeDailyQuota(userId);

        String key = props.getOss().normalizedKeyPrefix()
                + VIDEO_PREFIX + "/" + userId + "/" + MediaObjectKeys.randomToken() + ".mp4";
        MediaProperties.Video v = props.getVideo();
        Date expiration = Date.from(clock.instant().plusSeconds(v.getUploadTtlSeconds()));
        AliyunOssClient.PostPolicySignature sig =
                ossClient.signPostPolicy(expiration, buildConditions(key, v.getMaxBytes()));

        // 顺序即客户端表单字段顺序（file 由客户端追加在最后）。
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", key);
        fields.put("policy", sig.policyBase64());
        fields.put("OSSAccessKeyId", sig.accessKeyId());
        fields.put("signature", sig.signature());
        fields.put("success_action_status", "200");
        fields.put("Content-Type", VIDEO_CONTENT_TYPE);
        fields.put("x-oss-object-acl", "private");
        fields.put(AliyunOssClient.FORBID_OVERWRITE_HEADER, "true");
        return new VideoUploadPolicyResponse(hostFor(bucket, props.getOss().getEndpoint()), key, fields);
    }

    /** 策略条件（AD-3 六条）。抽成纯函数供 L0 断言 {@code jsonize()}。 */
    static PolicyConditions buildConditions(String key, long maxBytes) {
        PolicyConditions c = new PolicyConditions();
        c.addConditionItem(MatchMode.Exact, PolicyConditions.COND_KEY, key);
        c.addConditionItem(PolicyConditions.COND_CONTENT_LENGTH_RANGE, 1, maxBytes);
        c.addConditionItem(MatchMode.Exact, PolicyConditions.COND_CONTENT_TYPE, VIDEO_CONTENT_TYPE);
        c.addConditionItem(MatchMode.Exact, "x-oss-object-acl", "private");
        c.addConditionItem(MatchMode.Exact, AliyunOssClient.FORBID_OVERWRITE_HEADER, "true");
        c.addConditionItem(MatchMode.Exact, PolicyConditions.COND_SUCCESS_ACTION_STATUS, "200");
        return c;
    }

    /** {@code https://oss-ap-southeast-5.aliyuncs.com} + {@code tailtopia} → {@code https://tailtopia.oss-ap-southeast-5.aliyuncs.com}。 */
    static String hostFor(String bucket, String endpoint) {
        String ep = endpoint == null ? "" : endpoint.strip();
        if (!ep.contains("://")) {
            ep = "https://" + ep;
        }
        URI u = URI.create(ep);
        if (u.getHost() == null) {
            throw AppException.mediaCredential("媒体存储未配置，请联系运营");
        }
        return u.getScheme() + "://" + bucket + "." + u.getHost();
    }

    /** 当日（WIB）计数 +1，超限抛 429。 */
    private void consumeDailyQuota(long userId) {
        String day = LocalDate.now(clock.withZone(WIB)).format(DAY);
        String k = quotaKey(userId, day);
        Long count = redis.opsForValue().increment(k);
        if (count != null && count == 1L) {
            redis.expire(k, QUOTA_TTL);
        }
        if (count != null && count > props.getVideo().getDailyPolicyLimit()) {
            throw AppException.videoUploadQuotaExceeded("今日视频上传次数已用完，请明天再试");
        }
    }

    static String quotaKey(long userId, String wibDay) {
        return "rl:media:video-policy:" + userId + ":" + wibDay;
    }
}
