package com.tailtopia.shared.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 媒体基建配置（Story 2.1）。前缀 {@code media}。
 *
 * <p>全部从 env 注入，绝不入库；{@code .env.example} 仅放占位。包含阿里云 OSS 雅加达单桶寻址、
 * 预签名上传/读签名 URL TTL。AccessKey/Secret 仅运行环境注入。
 */
@ConfigurationProperties(prefix = "media")
public class MediaProperties {

    /** 阿里云 AccessKey（env 注入）。用于服务端 OSS 操作与预签名上传/读 URL 现签。 */
    private String accessKeyId = "";
    private String accessKeySecret = "";

    private final Oss oss = new Oss();
    private final SignedUrl signedUrl = new SignedUrl();
    private final Video video = new Video();

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

    public SignedUrl getSignedUrl() {
        return signedUrl;
    }

    public Video getVideo() {
        return video;
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
        /**
         * 对象 key 全局前缀。默认空 =生产行为不变；staging 与生产共用同一桶时，
         * 设为如 {@code stag/} 让 staging 新上传落到独立命名空间，与生产对象区分（换库不换地址同理，桶亦如此）。
         */
        private String keyPrefix = "";

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

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        /**
         * 归一化前缀：空/空白 → {@code ""}；否则去前导 {@code /}、补尾部 {@code /}。
         * 供 key 构造处统一前置，保证生产（空）零行为变化。
         */
        public String normalizedKeyPrefix() {
            if (keyPrefix == null || keyPrefix.isBlank()) {
                return "";
            }
            String p = keyPrefix.strip();
            while (p.startsWith("/")) {
                p = p.substring(1);
            }
            if (!p.endsWith("/")) {
                p = p + "/";
            }
            return p;
        }
    }

    public static class SignedUrl {
        /** 私密桶读签名 URL TTL（秒），默认 300（5 分钟）。短 TTL=降低泄漏面。 */
        private long ttlSeconds = 300;
        /** 预签名上传 URL TTL（秒），默认 600（上传需更长窗口，含弱网重试）。 */
        private long uploadTtlSeconds = 600;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public long getUploadTtlSeconds() {
            return uploadTtlSeconds;
        }

        public void setUploadTtlSeconds(long uploadTtlSeconds) {
            this.uploadTtlSeconds = uploadTtlSeconds;
        }
    }

    /** 专题线 video（Story 1.2 · AD-3）：视频 PostObject 上传策略。前缀 {@code media.video}。 */
    public static class Video {
        /** 策略有效期（秒），默认 1800。OSS 只在请求开始时检查过期（NFR-7：100MB @ 5Mbps 上行）。 */
        private long uploadTtlSeconds = 1800;
        /** 单个视频体积上限（字节），默认 100MB（决策 V-30）。 */
        private long maxBytes = 104_857_600L;
        /** 每账号每 WIB 自然日可申请的策略次数，默认 50。 */
        private int dailyPolicyLimit = 50;
        /**
         * 视频 CDN 域名（Story 1.4 · AD-11），如 {@code https://v.tailtopia.id}。空 = 不走 CDN，
         * 下发 OSS 源站地址、不刷新缓存（stag 可以不配；生产必须配）。
         */
        private String cdnBaseUrl = "";
        /** 文件公开对账的 cron（默认每分钟）。{@code -} = 关闭（测试环境）。 */
        private String aclReconcileCron = "0 * * * * *";
        /** 每轮对账最多处理的帖子数。 */
        private int aclReconcileBatch = 100;
        /** 期望状态变化后超过这么多秒仍未生效 → ERROR 告警。 */
        private long aclStuckAfterSeconds = 300;

        public String getCdnBaseUrl() {
            return cdnBaseUrl;
        }

        public void setCdnBaseUrl(String cdnBaseUrl) {
            this.cdnBaseUrl = cdnBaseUrl;
        }

        public String getAclReconcileCron() {
            return aclReconcileCron;
        }

        public void setAclReconcileCron(String aclReconcileCron) {
            this.aclReconcileCron = aclReconcileCron;
        }

        public int getAclReconcileBatch() {
            return aclReconcileBatch;
        }

        public void setAclReconcileBatch(int aclReconcileBatch) {
            this.aclReconcileBatch = aclReconcileBatch;
        }

        public long getAclStuckAfterSeconds() {
            return aclStuckAfterSeconds;
        }

        public void setAclStuckAfterSeconds(long aclStuckAfterSeconds) {
            this.aclStuckAfterSeconds = aclStuckAfterSeconds;
        }

        public long getUploadTtlSeconds() {
            return uploadTtlSeconds;
        }

        public void setUploadTtlSeconds(long uploadTtlSeconds) {
            this.uploadTtlSeconds = uploadTtlSeconds;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public int getDailyPolicyLimit() {
            return dailyPolicyLimit;
        }

        public void setDailyPolicyLimit(int dailyPolicyLimit) {
            this.dailyPolicyLimit = dailyPolicyLimit;
        }
    }
}
