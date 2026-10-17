package com.tailtopia.shared.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.media.dto.VideoUploadPolicyResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** L0：专题线 video Story 1.2 —— 视频 PostObject 策略（条件、key、字段、host、WIB 每日配额）。 */
class VideoUploadPolicyServiceTest {

    /** 2026-10-16 16:59:59Z = WIB 23:59:59。 */
    private static final Instant BEFORE_WIB_MIDNIGHT = Instant.parse("2026-10-16T16:59:59Z");

    private AliyunOssClient oss;
    private MediaProperties props;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        oss = mock(AliyunOssClient.class);
        props = new MediaProperties();
        props.getOss().setPublicBucket("tailtopia");
        props.getOss().setEndpoint("https://oss-ap-southeast-5.aliyuncs.com");
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);
        when(oss.signPostPolicy(any(), any()))
                .thenReturn(new AliyunOssClient.PostPolicySignature("cG9saWN5", "sig==", "AKID"));
    }

    private VideoUploadPolicyService service(Instant now) {
        return new VideoUploadPolicyService(oss, props, redis, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void conditionsLockFullKeySizeTypeAclOverwriteAndStatus() {
        String json = VideoUploadPolicyService.buildConditions("stag/video/9/abc.mp4", 104_857_600L).jsonize()
                .replace("\\/", "/"); // SDK 把 / 转义成 \/（合法 JSON，OSS 照常解析）
        assertThat(json)
                .contains("[\"eq\",\"$key\",\"stag/video/9/abc.mp4\"]")
                .contains("[\"content-length-range\",1,104857600]")
                .contains("[\"eq\",\"$Content-Type\",\"video/mp4\"]")
                .contains("[\"eq\",\"$x-oss-object-acl\",\"private\"]")
                .contains("[\"eq\",\"$x-oss-forbid-overwrite\",\"true\"]")
                .contains("[\"eq\",\"$success_action_status\",\"200\"]")
                // 锁的是完整 key，不能退化成前缀匹配。
                .doesNotContain("starts-with");
    }

    @Test
    void issuesKeyFieldsAndHost() {
        props.getOss().setKeyPrefix("stag/");
        VideoUploadPolicyResponse r = service(BEFORE_WIB_MIDNIGHT).issue(9L);

        assertThat(r.key()).matches("stag/video/9/[A-Za-z0-9_-]{22}\\.mp4");
        assertThat(r.host()).isEqualTo("https://tailtopia.oss-ap-southeast-5.aliyuncs.com");
        assertThat(List.copyOf(r.fields().keySet())).containsExactly(
                "key", "policy", "OSSAccessKeyId", "signature", "success_action_status",
                "Content-Type", "x-oss-object-acl", "x-oss-forbid-overwrite");
        assertThat(r.fields())
                .containsEntry("key", r.key())
                .containsEntry("policy", "cG9saWN5")
                .containsEntry("OSSAccessKeyId", "AKID")
                .containsEntry("signature", "sig==")
                .containsEntry("success_action_status", "200")
                .containsEntry("Content-Type", "video/mp4")
                .containsEntry("x-oss-object-acl", "private")
                .containsEntry("x-oss-forbid-overwrite", "true");
        assertThat(service(BEFORE_WIB_MIDNIGHT).issue(9L).key()).isNotEqualTo(r.key());
    }

    @Test
    void expirationIsNowPlusConfiguredTtl() {
        props.getVideo().setUploadTtlSeconds(1800);
        service(BEFORE_WIB_MIDNIGHT).issue(9L);
        ArgumentCaptor<Date> exp = ArgumentCaptor.forClass(Date.class);
        verify(oss).signPostPolicy(exp.capture(), any());
        assertThat(exp.getValue().toInstant()).isEqualTo(BEFORE_WIB_MIDNIGHT.plusSeconds(1800));
    }

    @Test
    void hostToleratesTrailingSlashAndMissingScheme() {
        assertThat(VideoUploadPolicyService.hostFor("b", "https://oss-x.aliyuncs.com/"))
                .isEqualTo("https://b.oss-x.aliyuncs.com");
        assertThat(VideoUploadPolicyService.hostFor("b", "oss-x.aliyuncs.com"))
                .isEqualTo("https://b.oss-x.aliyuncs.com");
    }

    @Test
    void fiftiethPassesFiftyFirstIs429AndNotSigned() {
        when(ops.increment(anyString())).thenReturn(50L);
        service(BEFORE_WIB_MIDNIGHT).issue(9L);

        when(ops.increment(anyString())).thenReturn(51L);
        org.mockito.Mockito.clearInvocations(oss);
        assertThatThrownBy(() -> service(BEFORE_WIB_MIDNIGHT).issue(9L))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(429);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.VIDEO_UPLOAD_QUOTA_EXCEEDED);
                });
        verify(oss, never()).signPostPolicy(any(), any());
    }

    @Test
    void quotaKeyRollsOverAtWibMidnightAndGetsTtlOnFirstHit() {
        service(BEFORE_WIB_MIDNIGHT).issue(9L);
        service(BEFORE_WIB_MIDNIGHT.plusSeconds(1)).issue(9L); // WIB 次日 00:00:00

        verify(ops).increment("rl:media:video-policy:9:20261016");
        verify(ops).increment("rl:media:video-policy:9:20261017");
        verify(redis).expire(eq("rl:media:video-policy:9:20261016"), eq(VideoUploadPolicyService.QUOTA_TTL));
    }

    @Test
    void missingBucketFailsFastWithoutCounting() {
        props.getOss().setPublicBucket("");
        assertThatThrownBy(() -> service(BEFORE_WIB_MIDNIGHT).issue(9L)).isInstanceOf(AppException.class);
        verify(ops, never()).increment(anyString());
    }

    /** code review：签名失败（无凭证 / 轮换出错）时退回这次计数，不白扣当日配额。 */
    @Test
    void signFailureRefundsQuota() {
        when(oss.signPostPolicy(any(), any())).thenThrow(AppException.mediaCredential("x"));
        assertThatThrownBy(() -> service(BEFORE_WIB_MIDNIGHT).issue(9L)).isInstanceOf(AppException.class);
        verify(ops).increment("rl:media:video-policy:9:20261016");
        verify(ops).decrement("rl:media:video-policy:9:20261016");
    }

    /** code review：endpoint 配错时在扣配额之前就失败。 */
    @Test
    void badEndpointFailsBeforeCounting() {
        props.getOss().setEndpoint("https://");
        assertThatThrownBy(() -> service(BEFORE_WIB_MIDNIGHT).issue(9L)).isInstanceOf(AppException.class);
        verify(ops, never()).increment(anyString());
    }
}
