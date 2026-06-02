package com.petgo.shared.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 媒体基建配置（Story 2.1）。前缀 {@code media}。
 *
 * <p>全部从 env 注入，绝不入库；{@code .env.example} 仅放占位。包含阿里云 OSS 雅加达双桶寻址、
 * STS AssumeRole 参数、签名 URL TTL。AccessKey/Secret 仅运行环境注入。
 */
@ConfigurationProperties(prefix = "media")
public class MediaProperties {

    /** 阿里云主账号 / 子账号 AccessKey（env 注入）。用于 STS AssumeRole 与服务端 OSS 操作。 */
    private String accessKeyId = "";
    private String accessKeySecret = "";

    private final Oss oss = new Oss();
    private final Sts sts = new Sts();
    private final SignedUrl signedUrl = new SignedUrl();

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public Oss getOss() {
        return oss;
    }

    public Sts getSts() {
        return sts;
    }

    public SignedUrl getSignedUrl() {
        return signedUrl;
    }

    /** OSS 双桶寻址。雅加达 region {@code ap-southeast-5}。 */
    public static class Oss {
        /** OSS endpoint，如 {@code https://oss-ap-southeast-5.aliyuncs.com}。 */
        private String endpoint = "https://oss-ap-southeast-5.aliyuncs.com";
        /** region id，如 {@code ap-southeast-5}（STS 服务寻址需要）。 */
        private String region = "ap-southeast-5";
        /** 公开桶①（CDN 分发）。 */
        private String publicBucket = "";
        /** 私密桶②（仅签名 URL）。 */
        private String privateBucket = "";
        /** 公开桶对外 CDN base URL（无尾斜杠），如 {@code https://cdn.petgo.example}。 */
        private String cdnBaseUrl = "";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getPublicBucket() {
            return publicBucket;
        }

        public void setPublicBucket(String publicBucket) {
            this.publicBucket = publicBucket;
        }

        public String getPrivateBucket() {
            return privateBucket;
        }

        public void setPrivateBucket(String privateBucket) {
            this.privateBucket = privateBucket;
        }

        public String getCdnBaseUrl() {
            return cdnBaseUrl;
        }

        public void setCdnBaseUrl(String cdnBaseUrl) {
            this.cdnBaseUrl = cdnBaseUrl;
        }
    }

    public static class Sts {
        /** AssumeRole 目标角色 ARN（env 注入）。 */
        private String roleArn = "";
        /** STS 服务 endpoint。 */
        private String endpoint = "sts.ap-southeast-5.aliyuncs.com";
        /** 临时凭证时效（秒），默认 900（15 分钟）。 */
        private long durationSeconds = 900;

        public String getRoleArn() {
            return roleArn;
        }

        public void setRoleArn(String roleArn) {
            this.roleArn = roleArn;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public long getDurationSeconds() {
            return durationSeconds;
        }

        public void setDurationSeconds(long durationSeconds) {
            this.durationSeconds = durationSeconds;
        }
    }

    public static class SignedUrl {
        /** 私密桶签名 URL TTL（秒），默认 300（5 分钟）。短 TTL=降低泄漏面。 */
        private long ttlSeconds = 300;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }
}
