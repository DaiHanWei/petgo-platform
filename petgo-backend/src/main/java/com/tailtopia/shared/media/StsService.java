package com.petgo.shared.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petgo.shared.error.AppException;
import com.petgo.shared.media.dto.StsCredentialResponse;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * STS 受限 scope 临时凭证签发（Story 2.1 · AC1）。
 *
 * <p>核心安全约束（NFR-7）：动态收窄 RAM 策略到「仅允许 {@code oss:PutObject} 到
 * {@code <bucket>/<scope前缀>/<userId>/*}」，禁止 list / 读他人 / 跨桶。桶与前缀由
 * {@link MediaScope} 决定。本类逻辑（桶选择 + policy 构造）纯函数，L0 可单测；真实 AssumeRole
 * 经 {@link AliyunStsClient} 注入（L2 真凭证下闭环）。
 */
@Service
public class StsService {

    private final AliyunStsClient stsClient;
    private final MediaProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StsService(AliyunStsClient stsClient, MediaProperties props) {
        this.stsClient = stsClient;
        this.props = props;
    }

    /**
     * 为当前用户签发收窄到目标桶+用户前缀的上传凭证。
     *
     * @param scope  PUBLIC（公开桶）/ PRIVATE（私密桶）
     * @param userId 当前 JWT 用户 id（前缀隔离，防越权）
     */
    public StsCredentialResponse issueUploadCredential(MediaScope scope, long userId) {
        String bucket = bucketFor(scope);
        if (bucket == null || bucket.isBlank()) {
            throw AppException.mediaCredential("媒体存储未配置，请联系运营");
        }
        String uploadDir = scope.prefix() + "/" + userId + "/";
        String policy = buildPolicy(bucket, uploadDir);
        String sessionName = "petgo-" + scope.name().toLowerCase() + "-" + userId;

        AliyunStsClient.AssumedCredential cred =
                stsClient.assumeRole(policy, props.getSts().getDurationSeconds(), sessionName);

        // 私密桶不返回 CDN base（私密对象只能走签名 URL，绝不给公开 URL）。
        String cdnBaseUrl = scope == MediaScope.PUBLIC ? blankToNull(props.getOss().getCdnBaseUrl()) : null;

        return new StsCredentialResponse(
                cred.accessKeyId(),
                cred.accessKeySecret(),
                cred.securityToken(),
                cred.expiration(),
                bucket,
                props.getOss().getRegion(),
                props.getOss().getEndpoint(),
                cdnBaseUrl,
                uploadDir);
    }

    private String bucketFor(MediaScope scope) {
        return switch (scope) {
            case PUBLIC -> props.getOss().getPublicBucket();
            case PRIVATE -> props.getOss().getPrivateBucket();
        };
    }

    /**
     * 构造最小权限 RAM 策略：仅 {@code oss:PutObject} 到 {@code bucket/uploadDir*}。
     * 不含 list / get / 其他前缀，杜绝越权（NFR-7）。
     */
    String buildPolicy(String bucket, String uploadDir) {
        Map<String, Object> statement = Map.of(
                "Effect", "Allow",
                "Action", List.of("oss:PutObject"),
                "Resource", List.of("acs:oss:*:*:" + bucket + "/" + uploadDir + "*"));
        Map<String, Object> policy = Map.of(
                "Version", "1",
                "Statement", List.of(statement));
        try {
            return objectMapper.writeValueAsString(policy);
        } catch (JsonProcessingException e) {
            throw AppException.mediaCredential("媒体凭证策略构造失败");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
