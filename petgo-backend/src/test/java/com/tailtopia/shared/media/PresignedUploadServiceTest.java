package com.tailtopia.shared.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.dto.UploadUrlResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * L0 单测（无 DB/外部）：scope→桶/前缀/ACL/publicUrl + 公开域 fail-fast。预签名 URL 现签经
 * {@link AliyunOssClient} mock 注入。
 */
class PresignedUploadServiceTest {

    private AliyunOssClient ossClient;
    private MediaProperties props;
    private PresignedUploadService service;

    @BeforeEach
    void setUp() {
        ossClient = Mockito.mock(AliyunOssClient.class);
        props = new MediaProperties();
        props.getOss().setPublicBucket("tailtopia");
        props.getOss().setPrivateBucket("tailtopia");
        props.getOss().setCdnBaseUrl("https://cdn.example");
        props.getSignedUrl().setUploadTtlSeconds(600);
        service = new PresignedUploadService(ossClient, props);
        when(ossClient.presignedPutUrl(anyString(), anyString(), anyString(), anyLong(), Mockito.anyBoolean()))
                .thenReturn("https://signed.example/put");
    }

    @Test
    void publicScopeSignsPublicReadAndReturnsPublicUrl() {
        UploadUrlResponse r = service.issue(MediaScope.PUBLIC, 42L, "image/jpeg");

        assertThat(r.method()).isEqualTo("PUT");
        assertThat(r.objectKey()).startsWith("public/42/").endsWith(".jpg");
        assertThat(r.headers()).containsEntry("Content-Type", "image/jpeg")
                .containsEntry("x-oss-object-acl", "public-read");
        assertThat(r.publicUrl()).isEqualTo("https://cdn.example/" + r.objectKey());

        // 现签时传入 publicRead=true、桶=tailtopia、TTL=600。
        ArgumentCaptor<Boolean> pub = ArgumentCaptor.forClass(Boolean.class);
        verify(ossClient).presignedPutUrl(eq("tailtopia"), eq(r.objectKey()), eq("image/jpeg"),
                eq(600L), pub.capture());
        assertThat(pub.getValue()).isTrue();
    }

    @Test
    void privateScopeNoAclNoPublicUrl() {
        UploadUrlResponse r = service.issue(MediaScope.PRIVATE, 7L, "image/jpeg");

        assertThat(r.objectKey()).startsWith("private/7/");
        assertThat(r.headers()).containsEntry("Content-Type", "image/jpeg")
                .doesNotContainKey("x-oss-object-acl");
        // 私密域绝不给公开 URL（读走签名 URL）。
        assertThat(r.publicUrl()).isNull();
        verify(ossClient).presignedPutUrl(anyString(), anyString(), anyString(), anyLong(), eq(false));
    }

    @Test
    void objectKeyIsUnenumerablePerCall() {
        String k1 = service.issue(MediaScope.PUBLIC, 42L, "image/jpeg").objectKey();
        String k2 = service.issue(MediaScope.PUBLIC, 42L, "image/jpeg").objectKey();
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void blankContentTypeFallsBackToOctetStream() {
        UploadUrlResponse r = service.issue(MediaScope.PRIVATE, 7L, null);
        assertThat(r.headers()).containsEntry("Content-Type", "application/octet-stream");
        assertThat(r.objectKey()).endsWith(".bin");
    }

    @Test
    void keyPrefixIsPrependedWhenConfigured() {
        // staging 同桶命名区分：设 key-prefix 后新上传对象落到独立前缀下（生产留空则零变化）。
        props.getOss().setKeyPrefix("stag/");
        UploadUrlResponse r = service.issue(MediaScope.PUBLIC, 42L, "image/jpeg");
        assertThat(r.objectKey()).startsWith("stag/public/42/").endsWith(".jpg");
        assertThat(r.publicUrl()).isEqualTo("https://cdn.example/" + r.objectKey());
    }

    @Test
    void keyPrefixIsNormalizedTolerantly() {
        // 无前导/尾斜杠亦可：归一化为 "stag/"。
        props.getOss().setKeyPrefix("stag");
        assertThat(service.issue(MediaScope.PRIVATE, 7L, "image/jpeg").objectKey())
                .startsWith("stag/private/7/");
    }

    @Test
    void missingBucketThrows() {
        props.getOss().setPublicBucket("");
        assertThatThrownBy(() -> service.issue(MediaScope.PUBLIC, 1L, "image/jpeg"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void publicScopeMissingCdnBaseThrows() {
        // 公开域漏配 CDN/公网 base → fail-fast，杜绝对象落桶却不可公网读。
        props.getOss().setCdnBaseUrl("");
        assertThatThrownBy(() -> service.issue(MediaScope.PUBLIC, 1L, "image/jpeg"))
                .isInstanceOf(AppException.class);
    }
}
