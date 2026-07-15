package com.tailtopia.pay.service;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.dto.CreateTopupRequest;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.dto.TopupOptions;
import com.tailtopia.pay.dto.TopupResponse;
import com.tailtopia.pay.dto.TopupTierDto;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * PawCoin 充值下单编排（Story 1.3）。把 1.1（意图/网关）接成下单闭环：
 * <ol>
 *   <li>校验档位（{@link TopupTierProvider}）+ 渠道（QRIS）；</li>
 *   <li>{@code PaymentIntentService.createIntent(PAWCOIN_TOPUP,...)} 建意图（幂等，写限流在其内）；</li>
 *   <li>{@code PaymentGateway.createCharge} 取付款载荷 + 网关订单号，回填意图（{@code attachCharge} 幂等）；</li>
 *   <li>回 {@link TopupResponse}（对外 token + 付款载荷，绝不含自增 id）。</li>
 * </ol>
 *
 * <p>外部 charge 调用<b>在 DB 事务之外</b>（createIntent/attachCharge 各自短事务），避免网络调用长持事务。
 * 同 {@code Idempotency-Key} 重放：createIntent 取回既有意图、attachCharge 幂等短路，不重复 charge。
 */
@Service
public class PawCoinTopupService {

    private static final String CURRENCY = "IDR";

    private final TopupTierProvider tierProvider;
    private final PaymentIntentService paymentIntentService;
    private final PaymentGateway gateway;
    private final PlatformConfigService platformConfig;

    public PawCoinTopupService(TopupTierProvider tierProvider, PaymentIntentService paymentIntentService,
            PaymentGateway gateway, PlatformConfigService platformConfig) {
        this.tierProvider = tierProvider;
        this.paymentIntentService = paymentIntentService;
        this.gateway = gateway;
        this.platformConfig = platformConfig;
    }

    /**
     * 充值选项（Story 1.5）：可选档位 + 是否暂停。档位与暂停态均来自后台可配（Story 9.2，
     * {@link TopupTierProvider} DB 实现 + {@code pawcoin_config.topup_paused}）。
     */
    public TopupOptions options() {
        List<TopupTierDto> tiers = tierProvider.tiers();
        return new TopupOptions(tiers, platformConfig.pawcoin().isTopupPaused());
    }

    public TopupResponse create(long userId, CreateTopupRequest req, String idempotencyKey) {
        TopupTierDto tier = tierProvider.byId(req.tierId());
        PayChannel channel = parsePayChannel(req.channel());
        long amount = tier.amount();

        PaymentIntentResponse intent = paymentIntentService.createIntent(
                userId, PaymentPurpose.PAWCOIN_TOPUP, channel, amount, CURRENCY, idempotencyKey);

        PaymentIntent entity = paymentIntentService.findByToken(intent.token())
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));

        String payload;
        if (entity.getGatewayRef() == null) {
            // 首次下单：向网关发起收款，回填订单号 + 载荷。
            ChargeResult charge = gateway.createCharge(new ChargeRequest(
                    entity.getPublicToken(), amount, CURRENCY, channel.name(), PaymentPurpose.PAWCOIN_TOPUP.name()));
            Map<String, Object> meta = new LinkedHashMap<>();
            if (charge.rawMeta() != null) {
                meta.putAll(charge.rawMeta());
            }
            if (charge.payload() != null) {
                meta.put("payload", charge.payload());
            }
            paymentIntentService.attachCharge(entity.getPublicToken(), charge.gatewayRef(), meta);
            payload = charge.payload();
        } else {
            // 幂等重放：返回既有载荷（下单时存入 gateway_meta.payload）。
            Map<String, Object> meta = entity.getGatewayMeta();
            payload = meta == null ? null : (String) meta.get("payload");
        }

        return new TopupResponse(intent.token(), channel.name(), amount, tier.coins(), payload);
    }

    /** 仅允许外部收款渠道 QRIS；PAWCOIN（站内余额）/非法值 → 422。 */
    private static PayChannel parsePayChannel(String raw) {
        if (raw != null) {
            try {
                PayChannel ch = PayChannel.valueOf(raw.trim().toUpperCase());
                if (ch == PayChannel.QRIS) {
                    return ch;
                }
            } catch (IllegalArgumentException ignored) {
                // 落到下方统一抛
            }
        }
        throw AppException.validation("非法支付方式");
    }
}
