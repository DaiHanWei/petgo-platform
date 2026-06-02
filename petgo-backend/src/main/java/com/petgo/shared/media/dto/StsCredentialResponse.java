package com.petgo.shared.media.dto;

/**
 * STS 上传凭证信封（返回给客户端直传 OSS 用）。Jackson NON_NULL：null 字段省略。
 *
 * <p>{@code uploadDir} 为收窄到「该用户该域」的对象前缀；客户端只能 PutObject 到此前缀下
 * （越权写其他前缀被 OSS 拒）。{@code cdnBaseUrl} 仅公开桶有值（私密桶只能走签名 URL）。
 *
 * @param accessKeyId     临时凭证 AK
 * @param accessKeySecret 临时凭证 SK（敏感，绝不落 INFO 日志）
 * @param securityToken   STS 安全令牌（敏感）
 * @param expiration      过期时刻（ISO-8601 UTC）
 * @param bucket          目标桶名
 * @param region          region id（如 ap-southeast-5）
 * @param endpoint        OSS endpoint
 * @param cdnBaseUrl      公开桶 CDN base（私密桶为 null）
 * @param uploadDir       允许写入的对象前缀（如 {@code public/42/}）
 */
public record StsCredentialResponse(
        String accessKeyId,
        String accessKeySecret,
        String securityToken,
        String expiration,
        String bucket,
        String region,
        String endpoint,
        String cdnBaseUrl,
        String uploadDir) {
}
