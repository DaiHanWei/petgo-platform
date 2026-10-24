package com.tailtopia.merchant.po.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 进货内部接口（契约 5.1「可达性」，AD-4）。前缀 {@code petgo.merchant.internal}。
 *
 * <p>{@code /internal/**} <b>只在独立端口上提供</b>：该端口不经 Cloudflare Tunnel 暴露、只在 docker 网络
 * {@code jbp-net} 内可达（部署时<b>不要</b>给它 {@code -p} 映射）。主端口上的 {@code /internal/**} 一律 404；
 * 独立端口上除 {@code /internal/**} 外的路径也一律 404 —— 内网里不顺带暴露整个 App API。
 *
 * <p>{@code port = 0}（默认）= 不开独立端口，内部接口整体关闭。凭证 env 注入，绝不入库、绝不落日志。
 */
@ConfigurationProperties(prefix = "petgo.merchant.internal")
public class MerchantInternalProperties {

    /** 独立内网端口；0 = 关闭。 */
    private int port = 0;

    /** 共享密钥 {@code X-Internal-Secret}；空 = 一律 401。 */
    private String secret = "";

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public boolean enabled() {
        return port > 0;
    }
}
