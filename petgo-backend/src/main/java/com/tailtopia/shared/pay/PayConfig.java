package com.tailtopia.shared.pay;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付基础设施装配（Story 1.1）。绑定 {@link PayProperties}（前缀 {@code petgo.pay}）并按 {@code mode}
 * 选择 {@link PaymentGateway} 实现：
 * <ul>
 *   <li>{@code mode=live} → {@link MidtransGateway}（真实 Midtrans，L2，需 {@code MIDTRANS_SERVER_KEY}）</li>
 *   <li>否则（默认 {@code stub}）→ {@link StubPaymentGateway}（免凭证，L0/L1 验状态机/去重）</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(PayProperties.class)
public class PayConfig {

    @Bean
    public PaymentGateway paymentGateway(PayProperties props) {
        if ("live".equalsIgnoreCase(props.getMode())) {
            return new MidtransGateway(props);
        }
        return new StubPaymentGateway(props);
    }
}
