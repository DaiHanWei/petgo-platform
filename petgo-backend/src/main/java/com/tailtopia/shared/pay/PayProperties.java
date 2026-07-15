package com.tailtopia.shared.pay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 支付网关接入配置（Story 1.1）。前缀 {@code petgo.pay}。
 *
 * <p>护栏：{@code serverKey} / {@code callbackToken} 全部从 env（{@code MIDTRANS_SERVER_KEY} /
 * {@code MIDTRANS_CALLBACK_TOKEN}）注入，<b>绝不入库、绝不落日志</b>；{@code .env.example} 仅放占位。
 * {@code mode=stub}（默认）走桩网关，使意图状态机 / 回调去重在无外部凭证下可 L0/L1 验证；
 * {@code mode=live} 才打真实 Midtrans（L2，需 sandbox 凭证）。
 */
@ConfigurationProperties(prefix = "petgo.pay")
public class PayProperties {

    /** {@code stub} | {@code live}。默认 stub，应用无凭证也能装配启动。 */
    private String mode = "stub";

    /**
     * {@code live} 时选哪家收款实现：{@code gempay}（默认，印尼实际落地）| {@code midtrans}（保留旧路径，未启用）。
     * {@code mode} 继续承担 prod fail-closed 启动护栏，本字段只在 {@code live} 下决定网关实现（决策 D-1）。
     */
    private String provider = "gempay";

    /** GemPay 收款接入配置（{@code petgo.pay.gempay.*}）。凭证 env 注入，绝不入库/落日志。 */
    private final Gempay gempay = new Gempay();

    /** Midtrans Server Key（env 注入，绝不入库/落日志）；Basic base64(serverKey:) 鉴权用。 */
    private String serverKey = "";

    /** Midtrans API base（默认 sandbox）。 */
    private String baseUrl = "https://api.sandbox.midtrans.com";

    /**
     * 回调校验令牌（stub 直比 {@code signature_key} 字段；live 用 serverKey 算 SHA-512，此字段仅
     * stub/内网白名单兜底用）。env 注入，绝不入库/落日志。
     */
    private String callbackToken = "";

    /** 单次网关调用超时（秒）。 */
    private int timeoutSeconds = 10;

    /**
     * Midtrans Iris/Disbursement API Key（Story 4.6 退款出款；env {@code MIDTRANS_IRIS_API_KEY} 注入，
     * 绝不入库/落日志）。与收款侧 {@link #serverKey} 独立（Iris 是独立产品）。mode=live 出款时必需（缺则出款拒）。
     */
    private String irisApiKey = "";

    /** Midtrans Iris API base（默认 sandbox）。 */
    private String irisBaseUrl = "https://app.sandbox.midtrans.com/iris";

    /**
     * PawCoin 充值是否暂停（Story 1.5，env {@code PAWCOIN_TOPUP_PAUSED}，默认 false）。浮存门槛触发时由
     * <b>运营/工程手动置 true 重启</b>（AB-6C：V1.1 后端不做一键硬暂停，仅暴露此 flag 供前端渲染暂停态）。
     */
    private boolean topupPaused = false;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Gempay getGempay() {
        return gempay;
    }

    /**
     * GemPay 收款接入配置。{@code merchantSecret} 只进 md5 签名，绝不作为字段上送、绝不入库/落日志；
     * {@code .env.example} 仅放占位。
     */
    public static class Gempay {

        /** 收款 API base（默认 sandbox）；正式 {@code https://api.gempay.online/v1}。 */
        private String baseUrl = "https://sandbox-api.gempay.online/v1";

        /** 放款 API base（Story 4.6 两步 inquiry→transfer 用）；默认 sandbox。 */
        private String disburseUrl = "https://sandbox-api.gempay.online/api";

        /** 商户号（env {@code GEMPAY_MERCHANT_ID}）。 */
        private String merchantId = "";

        /** 项目号（env {@code GEMPAY_PROJECT_NO}）。 */
        private String projectNo = "";

        /** 商户密钥（env {@code GEMPAY_MERCHANT_SECRET}，只进 md5，绝不入库/落日志）。 */
        private String merchantSecret = "";

        /** 收款回调地址（每次 /direct 请求上送）；默认线上 {@code https://api.tailtopia.id/pay/callback}。 */
        private String callbackUrl = "https://api.tailtopia.id/pay/callback";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getDisburseUrl() {
            return disburseUrl;
        }

        public void setDisburseUrl(String disburseUrl) {
            this.disburseUrl = disburseUrl;
        }

        public String getMerchantId() {
            return merchantId;
        }

        public void setMerchantId(String merchantId) {
            this.merchantId = merchantId;
        }

        public String getProjectNo() {
            return projectNo;
        }

        public void setProjectNo(String projectNo) {
            this.projectNo = projectNo;
        }

        public String getMerchantSecret() {
            return merchantSecret;
        }

        public void setMerchantSecret(String merchantSecret) {
            this.merchantSecret = merchantSecret;
        }

        public String getCallbackUrl() {
            return callbackUrl;
        }

        public void setCallbackUrl(String callbackUrl) {
            this.callbackUrl = callbackUrl;
        }
    }

    public String getServerKey() {
        return serverKey;
    }

    public void setServerKey(String serverKey) {
        this.serverKey = serverKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCallbackToken() {
        return callbackToken;
    }

    public void setCallbackToken(String callbackToken) {
        this.callbackToken = callbackToken;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getIrisApiKey() {
        return irisApiKey;
    }

    public void setIrisApiKey(String irisApiKey) {
        this.irisApiKey = irisApiKey;
    }

    public String getIrisBaseUrl() {
        return irisBaseUrl;
    }

    public void setIrisBaseUrl(String irisBaseUrl) {
        this.irisBaseUrl = irisBaseUrl;
    }

    public boolean isTopupPaused() {
        return topupPaused;
    }

    public void setTopupPaused(boolean topupPaused) {
        this.topupPaused = topupPaused;
    }
}
