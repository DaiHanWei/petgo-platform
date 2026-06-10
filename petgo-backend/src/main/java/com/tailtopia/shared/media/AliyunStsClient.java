package com.tailtopia.shared.media;

/**
 * STS AssumeRole 调用边界（薄接口）。把真实阿里云 SDK 调用与 {@link StsService} 的
 * 策略构造逻辑解耦：业务逻辑（桶/前缀/policy 收窄）可在 L0 单测，真实签发留 L2。
 */
public interface AliyunStsClient {

    /**
     * 以收窄后的 {@code policy} 执行 AssumeRole，返回临时凭证。
     *
     * @param policy          动态收窄的 RAM 策略 JSON（仅允许目标桶+用户前缀 PutObject）
     * @param durationSeconds 凭证时效（秒）
     * @param sessionName     RAM 会话名（审计用，含 userId）
     */
    AssumedCredential assumeRole(String policy, long durationSeconds, String sessionName);

    /** STS 返回的临时凭证（expiration 为 ISO-8601 UTC 字符串）。 */
    record AssumedCredential(
            String accessKeyId,
            String accessKeySecret,
            String securityToken,
            String expiration) {
    }
}
