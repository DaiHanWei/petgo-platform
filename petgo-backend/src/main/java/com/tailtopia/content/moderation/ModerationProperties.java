package com.tailtopia.content.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内容审核配置（内容审核 Story 1）。前缀 {@code app.moderation}。
 *
 * <p>护栏：AK/Secret 全部从 env 注入，<b>绝不入库、绝不落日志</b>；{@code .env.example} 仅占位。
 * {@code mode=stub}（默认）走打桩客户端，无凭证即可启动并验评分/降级状态机（L0/L1）；
 * {@code mode=live} 才打真实阿里云内容安全（L2，需真实 AK + 印尼语实测）。
 */
@ConfigurationProperties(prefix = "app.moderation")
public class ModerationProperties {

    /** {@code stub}（默认）| {@code live}。 */
    private String mode = "stub";

    /** RISKY 阈值（评分 ≥ 此值且未命中 L1 → RISKY）。默认 0.8。 */
    private double riskThreshold = 0.8;

    /** 文本审核单次超时（ms，SLA ≤1s）。 */
    private int textTimeoutMs = 1000;

    /** 图像审核单次超时（ms，SLA ≤2s）。 */
    private int imageTimeoutMs = 2000;

    private final Aliyun aliyun = new Aliyun();
    private final ImageThreshold imageThreshold = new ImageThreshold();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public double getRiskThreshold() {
        return riskThreshold;
    }

    public void setRiskThreshold(double riskThreshold) {
        this.riskThreshold = riskThreshold;
    }

    public int getTextTimeoutMs() {
        return textTimeoutMs;
    }

    public void setTextTimeoutMs(int textTimeoutMs) {
        this.textTimeoutMs = textTimeoutMs;
    }

    public int getImageTimeoutMs() {
        return imageTimeoutMs;
    }

    public void setImageTimeoutMs(int imageTimeoutMs) {
        this.imageTimeoutMs = imageTimeoutMs;
    }

    public Aliyun getAliyun() {
        return aliyun;
    }

    public ImageThreshold getImageThreshold() {
        return imageThreshold;
    }

    /** 阿里云内容安全（green20220302）接入参数。AK 可复用 OSS 段或独立注入。 */
    public static class Aliyun {
        /** 国际站 Region（新加坡），覆盖印尼语。 */
        private String region = "ap-southeast-1";
        private String endpoint = "green-cip.ap-southeast-1.aliyuncs.com";
        /** env 注入，绝不入库/落日志；留空则复用 OSS 的 ALIYUN_ACCESS_KEY_ID。 */
        private String accessKeyId = "";
        private String accessKeySecret = "";

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

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
    }

    /** 图像分类高置信违规阈值（§4.2）。命中即 IMAGE_BLOCKED。 */
    public static class ImageThreshold {
        private double porn = 0.85;
        private double violence = 0.80;
        private double contraband = 0.75;

        public double getPorn() {
            return porn;
        }

        public void setPorn(double porn) {
            this.porn = porn;
        }

        public double getViolence() {
            return violence;
        }

        public void setViolence(double violence) {
            this.violence = violence;
        }

        public double getContraband() {
            return contraband;
        }

        public void setContraband(double contraband) {
            this.contraband = contraband;
        }
    }
}
