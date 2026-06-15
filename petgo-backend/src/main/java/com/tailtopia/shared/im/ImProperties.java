package com.tailtopia.shared.im;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 腾讯 IM 配置（Story 5.5，env 注入，绝不入库）。前缀 {@code petgo.im}。
 *
 * <p>{@code mode=stub}（默认）走 {@link StubTencentImClient}（无凭证验状态机/接单 CAS，L0/L1）；
 * {@code mode=live} 才接真实腾讯 IM（L2，需真机 + SDKAppID/SecretKey）。
 * SecretKey 绝不下发客户端、绝不入日志。
 */
@ConfigurationProperties(prefix = "petgo.im")
public class ImProperties {

    /** stub（默认，免凭证）/ live（真实 IM，L2）。 */
    private String mode = "stub";
    /** 腾讯 IM SDKAppID（env 注入）。 */
    private String sdkAppId = "";
    /** 腾讯 IM SecretKey（env 注入，绝不下发/入日志）。 */
    private String secretKey = "";
    /** UserSig 有效期（秒，默认 1 天）。 */
    private long userSigTtlSeconds = 86400;
    /** IM 服务端回调签名校验 token（/im/callback 白名单 + 签名）。 */
    private String callbackToken = "";
    /**
     * 腾讯 IM REST API 基址（Story 5.5 live，按数据中心选德国/欧洲，与后端同区）。
     * 形如 {@code https://adminapiger.im.qcloud.com}（德国）/ {@code https://console.tim.qq.com}（国内）。
     */
    private String restBaseUrl = "https://adminapiger.im.qcloud.com";
    /**
     * REST 调用的管理员 IM 标识（{@code Identifier}，App 管理员账号，需在控制台配置为 App 管理员）。
     * 用其 UserSig 作为 REST 鉴权身份；不计 MAU（仅服务端用）。
     */
    private String adminIdentifier = "administrator";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getSdkAppId() {
        return sdkAppId;
    }

    public void setSdkAppId(String sdkAppId) {
        this.sdkAppId = sdkAppId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public long getUserSigTtlSeconds() {
        return userSigTtlSeconds;
    }

    public void setUserSigTtlSeconds(long userSigTtlSeconds) {
        this.userSigTtlSeconds = userSigTtlSeconds;
    }

    public String getCallbackToken() {
        return callbackToken;
    }

    public void setCallbackToken(String callbackToken) {
        this.callbackToken = callbackToken;
    }

    public String getRestBaseUrl() {
        return restBaseUrl;
    }

    public void setRestBaseUrl(String restBaseUrl) {
        this.restBaseUrl = restBaseUrl;
    }

    public String getAdminIdentifier() {
        return adminIdentifier;
    }

    public void setAdminIdentifier(String adminIdentifier) {
        this.adminIdentifier = adminIdentifier;
    }
}
