package com.tailtopia.shared.pay;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 支付基础设施装配（Story 1.1）。绑定 {@link PayProperties}（前缀 {@code petgo.pay}）并按 {@code mode}
 * 选择 {@link PaymentGateway} 实现：
 * <ul>
 *   <li>{@code mode=live} → {@link MidtransGateway}（真实 Midtrans，L2，需 {@code MIDTRANS_SERVER_KEY}）</li>
 *   <li>否则（默认 {@code stub}）→ {@link StubPaymentGateway}（免凭证，L0/L1 验状态机/去重）</li>
 * </ul>
 *
 * <p><b>fail-closed 启动护栏（Review P1）</b>：支付是资金入口，配置错误绝不能静默放行伪造回调。
 * 故装配时即校验——{@code prod} 环境必须 {@code mode=live} 且凭证齐全，否则拒绝启动，
 * 而非以放行一切回调的桩上生产。（同记忆库「prod 收假 idToken」事故的防线。）
 */
@Configuration
@EnableConfigurationProperties(PayProperties.class)
public class PayConfig {

    @Bean
    public PaymentGateway paymentGateway(PayProperties props, Environment env) {
        boolean prod = env.matchesProfiles("prod");
        if ("live".equalsIgnoreCase(props.getMode())) {
            // live 必须有 serverKey + callbackToken，否则验签形同虚设（可被伪造）。启动即拒。
            if (isBlank(props.getServerKey()) || isBlank(props.getCallbackToken())) {
                throw new IllegalStateException(
                        "petgo.pay.mode=live 需配置 MIDTRANS_SERVER_KEY 与 MIDTRANS_CALLBACK_TOKEN，缺失即拒启动");
            }
            return new MidtransGateway(props);
        }
        // 非 live：桩网关验签宽松，绝不允许在 prod 环境装配（否则回调门大开）。
        if (prod) {
            throw new IllegalStateException("prod 环境禁止 petgo.pay.mode=stub —— 必须 mode=live 并配齐 Midtrans 凭证");
        }
        return new StubPaymentGateway(props);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
