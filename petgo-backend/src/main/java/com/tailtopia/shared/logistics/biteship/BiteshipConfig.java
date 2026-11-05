package com.tailtopia.shared.logistics.biteship;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * Biteship 客户端装配（v1.4.2 shop Story 1.1，照 {@code PayConfig} 的 fail-closed 思路）。
 *
 * <ul>
 *   <li>{@code mode=live}：key 必须以 {@code biteship_live.} 开头；{@code mode=sandbox}：必须以
 *       {@code biteship_test.} 开头；</li>
 *   <li>{@code prod & !stag} 只允许 {@code live}（stag 容器是 prod + stag 双 profile，允许 stub / sandbox）；</li>
 *   <li>其余（dev、测试、云端）默认 {@code stub}。</li>
 * </ul>
 *
 * <p>🔴 <b>决策 C-5 覆盖原「生产缺 key 拒启动」</b>：在 {@code prod} profile 下（含 stag）上述任一条
 * 不满足时<b>不拒绝启动</b>，ERROR 日志 + 装配 {@link UnavailableBiteshipClient}（询价一律失败、
 * 结算不可提交）；非 prod 环境配置错误仍拒启动（开发者显式配错应立即暴露）。
 * 生产绝不回退到桩报价。
 */
@Configuration
@EnableConfigurationProperties(BiteshipProperties.class)
public class BiteshipConfig {

    private static final Logger log = LoggerFactory.getLogger(BiteshipConfig.class);

    static final String LIVE_PREFIX = "biteship_live.";
    static final String TEST_PREFIX = "biteship_test.";

    @Bean
    public BiteshipClient biteshipClient(BiteshipProperties props, Environment env) {
        return build(props, env, RestClient.builder(), true);
    }

    /**
     * 桩测试钩子（v1.4.2 shop Story 5.1 AC3.2）：<b>仅当实际装配的是桩客户端时</b>才有实例；live / sandbox / 不可用
     * 时本方法返回 null（容器登记为空 bean，{@code ObjectProvider#getIfAvailable} 得 null）。
     * 🔴 {@link BiteshipStubScript} 不实现 {@link BiteshipClient}，避免按类型注入出现两个候选。
     */
    @Bean
    public BiteshipStubScript biteshipStubScript(BiteshipClient client) {
        return client instanceof StubBiteshipClient stub ? new BiteshipStubScript(stub) : null;
    }

    /** 包级可见：测试可注入绑定了 {@code MockRestServiceServer} 的 builder。 */
    BiteshipClient build(BiteshipProperties props, Environment env, RestClient.Builder builder,
            boolean applyTimeouts) {
        boolean prod = env.matchesProfiles("prod");
        boolean prodOnly = env.matchesProfiles("prod & !stag");
        String mode = props.getMode() == null ? "" : props.getMode().trim().toLowerCase(Locale.ROOT);
        String key = props.getApiKey() == null ? "" : props.getApiKey().trim();

        if (props.containsForbiddenCourier()) {
            log.warn("BITESHIP_COURIERS contains jntcargo; removed (its price always includes insurance)");
        }

        switch (mode) {
            case "live" -> {
                if (!key.startsWith(LIVE_PREFIX)) {
                    return misconfigured(prod, "petgo.shop.biteship.mode=live requires BITESHIP_API_KEY "
                            + "with prefix " + LIVE_PREFIX + " (missing or prefix mismatch)");
                }
                return new LiveBiteshipClient(props, "LIVE", builder, applyTimeouts);
            }
            case "sandbox" -> {
                if (prodOnly) {
                    return misconfigured(true, "prod (non-stag) must use petgo.shop.biteship.mode=live");
                }
                if (!key.startsWith(TEST_PREFIX)) {
                    return misconfigured(prod, "petgo.shop.biteship.mode=sandbox requires BITESHIP_API_KEY "
                            + "with prefix " + TEST_PREFIX + " (missing or prefix mismatch)");
                }
                return new LiveBiteshipClient(props, "SANDBOX", builder, applyTimeouts);
            }
            case "stub" -> {
                if (prodOnly) {
                    return misconfigured(true, "prod (non-stag) must use petgo.shop.biteship.mode=live");
                }
                return new StubBiteshipClient();
            }
            default -> {
                return misconfigured(prod, "unknown petgo.shop.biteship.mode: " + mode);
            }
        }
    }

    private static BiteshipClient misconfigured(boolean prod, String message) {
        if (!prod) {
            throw new IllegalStateException(message);
        }
        // C-5：生产不拒启动；询价一律 quote-failed，结算不可提交
        log.error("Biteship not available: {}; all shipping quotes will fail (quote-failed)", message);
        return new UnavailableBiteshipClient();
    }
}
