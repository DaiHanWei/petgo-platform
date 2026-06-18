package com.tailtopia.shared.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.dto.StsCredentialResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * L0 单元测试（无 DB/外部）：桶选择 + scope 收窄 policy + 私密桶不给 CDN（AC1/AC3 逻辑面）。
 */
class StsServiceTest {

    private AliyunStsClient stsClient;
    private MediaProperties props;
    private StsService service;

    @BeforeEach
    void setUp() {
        stsClient = Mockito.mock(AliyunStsClient.class);
        props = new MediaProperties();
        props.getOss().setPublicBucket("petgo-public");
        props.getOss().setPrivateBucket("petgo-private");
        props.getOss().setCdnBaseUrl("https://cdn.petgo.example");
        props.getOss().setRegion("ap-southeast-5");
        props.getSts().setDurationSeconds(900);
        service = new StsService(stsClient, props);
        when(stsClient.assumeRole(anyString(), anyLong(), anyString()))
                .thenReturn(new AliyunStsClient.AssumedCredential("ak", "sk", "tok", "2026-06-02T00:00:00Z"));
    }

    @Test
    void publicScopeSelectsPublicBucketAndCdn() {
        StsCredentialResponse resp = service.issueUploadCredential(MediaScope.PUBLIC, 42L);
        assertThat(resp.bucket()).isEqualTo("petgo-public");
        assertThat(resp.uploadDir()).isEqualTo("public/42/");
        assertThat(resp.cdnBaseUrl()).isEqualTo("https://cdn.petgo.example");
        assertThat(resp.accessKeySecret()).isEqualTo("sk");
    }

    @Test
    void privateScopeSelectsPrivateBucketAndNoCdn() {
        StsCredentialResponse resp = service.issueUploadCredential(MediaScope.PRIVATE, 7L);
        assertThat(resp.bucket()).isEqualTo("petgo-private");
        assertThat(resp.uploadDir()).isEqualTo("private/7/");
        // 私密桶绝不给公开 CDN URL（只走签名 URL）。
        assertThat(resp.cdnBaseUrl()).isNull();
    }

    @Test
    void publicPolicyAllowsPutAndPutAclScopedToUserPrefix() {
        service.issueUploadCredential(MediaScope.PUBLIC, 42L);
        ArgumentCaptor<String> policyCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(stsClient).assumeRole(policyCaptor.capture(), eq(900L), anyString());
        String policy = policyCaptor.getValue();
        assertThat(policy).contains("oss:PutObject");
        // 单桶 + 对象级 ACL：PUBLIC 域允许打 public-read ACL（仍限同一前缀）。
        assertThat(policy).contains("oss:PutObjectAcl");
        assertThat(policy).contains("petgo-public/public/42/*");
        // 最小权限：不含 list / get 他人前缀。
        assertThat(policy).doesNotContain("oss:ListObjects");
        assertThat(policy).doesNotContain("oss:GetObject");
    }

    @Test
    void privatePolicyIsPutOnlyNoAcl() {
        service.issueUploadCredential(MediaScope.PRIVATE, 7L);
        ArgumentCaptor<String> policyCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(stsClient).assumeRole(policyCaptor.capture(), eq(900L), anyString());
        String policy = policyCaptor.getValue();
        assertThat(policy).contains("oss:PutObject");
        assertThat(policy).contains("petgo-private/private/7/*");
        // 私密域绝不给 ACL 权（读走签名 URL）。
        assertThat(policy).doesNotContain("oss:PutObjectAcl");
        assertThat(policy).doesNotContain("oss:GetObject");
    }

    @Test
    void missingBucketConfigThrows() {
        props.getOss().setPublicBucket("");
        assertThatThrownBy(() -> service.issueUploadCredential(MediaScope.PUBLIC, 1L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void publicScopeMissingCdnBaseThrows() {
        // 公开域漏配 CDN/公网 base → fail-fast，杜绝前端误判私有、对象上传后不可公网读。
        props.getOss().setCdnBaseUrl("");
        assertThatThrownBy(() -> service.issueUploadCredential(MediaScope.PUBLIC, 1L))
                .isInstanceOf(AppException.class);
    }
}
