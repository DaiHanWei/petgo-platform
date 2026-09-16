package com.tailtopia.shop.order.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 新订单 Lark 提醒配置（Story 3-4）。前缀 {@code petgo.shop.order-notify}。
 *
 * <p>形状照 {@code petgo.lark-content}：{@code mode} 默认 {@code off}，
 * 合并到任何环境都不会意外出网；凭证 env 注入，<b>绝不入库、绝不落日志</b>，
 * {@code .env.example} 只放占位。
 *
 * <p>🔴 <b>收件人不在代码里出现</b>（AC8）：OD-5「发给谁 / 窗口多长 / 夜间是否静默」
 * 尚未拍板，所以群 id 一律从配置读。拍板后改 env 即可，不需要改码、不需要发版。
 */
@ConfigurationProperties(prefix = "petgo.shop.order-notify")
public class ShopOrderNotifyProperties {

    /** {@code off} | {@code live}。默认 off —— 未配置的环境（本地 / CI / prod 未开）即安全静默。 */
    private String mode = "off";

    /** Lark 企业自建应用凭证（env 注入）。 */
    private String appId = "";

    /** 应用密钥（env 注入，绝不入库 / 落日志）。 */
    private String appSecret = "";

    /** 开放平台域名。海外租户 open.larksuite.com；国内换 open.feishu.cn。 */
    private String baseUrl = "https://open.larksuite.com";

    /**
     * 收件人 id（群 chat_id 或个人 open_id）。
     *
     * <p>🔴 <b>OD-5 未拍板，默认空 —— 空即视同 off，不发。</b> 代码里不出现任何具体 id。
     */
    private String receiveId = "";

    /** 收件人 id 的类型：{@code chat_id} / {@code open_id} / {@code user_id} 等（Lark 的 receive_id_type）。 */
    private String receiveIdType = "chat_id";

    /**
     * 汇总窗口（分钟）。同一窗口内的订单**合成一条消息**（AC3）。
     *
     * <p>默认 10 与 SHOP-NFR-03「10 分钟内送达」对齐 —— 窗口比它长就必然超时。
     */
    private int windowMinutes = 10;

    /** 单条汇总消息最多列几笔订单。超出的留到下一轮，避免一条消息长到没人读。 */
    private int maxOrdersPerMessage = 50;

    /** 投递失败重试上限，超过转 FAILED 不再重试。 */
    private int maxRetries = 3;

    /** 出网超时（秒）。🔴 必须有：没有超时的出网会把异步线程池挂满。 */
    private int timeoutSeconds = 10;

    /**
     * 夜间静默（AC8）。<b>默认关闭</b> —— OD-5 定值前不启用。
     * 开启后 {@code [quietStartHour, quietEndHour)} 之间不投递，行保持 PENDING 等天亮。
     */
    private boolean quietHoursEnabled = false;

    /** 夜间静默起始小时（WIB，含）。 */
    private int quietStartHour = 22;

    /** 夜间静默结束小时（WIB，不含）。 */
    private int quietEndHour = 8;

    public boolean isLive() {
        return "live".equalsIgnoreCase(mode == null ? "" : mode.trim())
                && receiveId != null && !receiveId.isBlank();
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getReceiveId() {
        return receiveId;
    }

    public void setReceiveId(String receiveId) {
        this.receiveId = receiveId;
    }

    public String getReceiveIdType() {
        return receiveIdType;
    }

    public void setReceiveIdType(String receiveIdType) {
        this.receiveIdType = receiveIdType;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(int windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public int getMaxOrdersPerMessage() {
        return maxOrdersPerMessage;
    }

    public void setMaxOrdersPerMessage(int maxOrdersPerMessage) {
        this.maxOrdersPerMessage = maxOrdersPerMessage;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isQuietHoursEnabled() {
        return quietHoursEnabled;
    }

    public void setQuietHoursEnabled(boolean quietHoursEnabled) {
        this.quietHoursEnabled = quietHoursEnabled;
    }

    public int getQuietStartHour() {
        return quietStartHour;
    }

    public void setQuietStartHour(int quietStartHour) {
        this.quietStartHour = quietStartHour;
    }

    public int getQuietEndHour() {
        return quietEndHour;
    }

    public void setQuietEndHour(int quietEndHour) {
        this.quietEndHour = quietEndHour;
    }
}
