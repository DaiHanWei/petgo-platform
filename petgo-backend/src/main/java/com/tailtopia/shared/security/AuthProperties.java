package com.petgo.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auth 配置（env 注入，绝不入库）。
 * 前缀 {@code petgo.auth}：JWT 签名密钥 / 令牌 TTL / Google client id。
 */
@ConfigurationProperties(prefix = "petgo.auth")
public class AuthProperties {

    /** 自签 JWT 元信息 + 令牌时效。 */
    private Jwt jwt = new Jwt();
    private Refresh refresh = new Refresh();
    private Google google = new Google();

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Refresh getRefresh() {
        return refresh;
    }

    public void setRefresh(Refresh refresh) {
        this.refresh = refresh;
    }

    public Google getGoogle() {
        return google;
    }

    public void setGoogle(Google google) {
        this.google = google;
    }

    public static class Jwt {
        /** HMAC 签名密钥（≥32 字节）。env 注入。 */
        private String secret = "";
        private String issuer = "petgo";
        /** access token 时效（秒），默认 15 分钟。 */
        private long accessTtlSeconds = 900;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public long getAccessTtlSeconds() {
            return accessTtlSeconds;
        }

        public void setAccessTtlSeconds(long accessTtlSeconds) {
            this.accessTtlSeconds = accessTtlSeconds;
        }
    }

    public static class Refresh {
        /** refresh token 时效（天），默认 30 天。 */
        private long ttlDays = 30;

        public long getTtlDays() {
            return ttlDays;
        }

        public void setTtlDays(long ttlDays) {
            this.ttlDays = ttlDays;
        }
    }

    public static class Google {
        /** Google OAuth client id（aud 校验）。env 注入。 */
        private String clientId = "";

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }
    }
}
