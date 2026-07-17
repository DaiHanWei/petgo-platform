package com.tailtopia.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.config.domain.PawCoinConfig;
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
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0：充值下单编排（mock 档位/意图 service/网关/配置）。非法档位/渠道拒绝；合法下单调 createIntent + charge +
 * attachCharge；幂等重放不重复 charge。档位为 {@link TopupTierDto}（9.2 起 DB 可配），暂停态读 PawCoin 配置。
 */
@ExtendWith(MockitoExtension.class)
class PawCoinTopupServiceTest {

    private static final TopupTierDto TIER_10K = new TopupTierDto("10k", 10_000L, 10_000L);
    private static final TopupTierDto TIER_100K = new TopupTierDto("100k", 100_000L, 100_000L);

    @Mock
    TopupTierProvider tierProvider;
    @Mock
    PaymentIntentService paymentIntentService;
    @Mock
    PaymentGateway gateway;
    @Mock
    PlatformConfigService platformConfig;
    @Mock
    PawCoinConfig pawcoinConfig;

    private PawCoinTopupService service() {
        return new PawCoinTopupService(tierProvider, paymentIntentService, gateway, platformConfig);
    }

    private PaymentIntentResponse intentResp(String token) {
        return new PaymentIntentResponse(token, "PAWCOIN_TOPUP", "QRIS", 10_000L, "IDR", "PENDING");
    }

    @Test
    void rejectsInvalidTier() {
        when(tierProvider.byId("999")).thenThrow(AppException.validation("非法充值档位"));
        assertThatThrownBy(() -> service().create(1L, new CreateTopupRequest("999", "QRIS"), "k"))
                .isInstanceOf(AppException.class);
        verify(paymentIntentService, never())
                .createIntent(anyLong(), any(), any(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void rejectsInvalidChannel() {
        when(tierProvider.byId("10k")).thenReturn(TIER_10K);
        assertThatThrownBy(() -> service().create(1L, new CreateTopupRequest("10k", "PAWCOIN"), "k"))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service().create(1L, new CreateTopupRequest("10k", "BOGUS"), "k"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void createsIntentChargesAndReturnsPayload() {
        when(tierProvider.byId("10k")).thenReturn(TIER_10K);
        when(paymentIntentService.createIntent(eq(1L), eq(PaymentPurpose.PAWCOIN_TOPUP), eq(PayChannel.QRIS),
                eq(10_000L), eq("IDR"), eq("k"), any())).thenReturn(intentResp("tok-1"));
        PaymentIntent fresh = PaymentIntent.create(1L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS,
                10_000L, "IDR", "tok-1"); // gatewayRef 为 null → 走首次 charge
        when(paymentIntentService.findByToken("tok-1")).thenReturn(Optional.of(fresh));
        when(gateway.createCharge(any())).thenReturn(new ChargeResult("gw-1", "qr://pay", Map.of("stub", true)));

        TopupResponse resp = service().create(1L, new CreateTopupRequest("10k", "QRIS"), "k");

        assertThat(resp.intentToken()).isEqualTo("tok-1");
        assertThat(resp.channel()).isEqualTo("QRIS");
        assertThat(resp.amount()).isEqualTo(10_000L);
        assertThat(resp.coins()).isEqualTo(10_000L);
        assertThat(resp.payload()).isEqualTo("qr://pay");
        verify(paymentIntentService).attachCharge(eq("tok-1"), eq("gw-1"), any());
    }

    @Test
    void idempotentReplayReturnsExistingPayloadWithoutRecharge() {
        when(tierProvider.byId("10k")).thenReturn(TIER_10K);
        when(paymentIntentService.createIntent(anyLong(), any(), any(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(intentResp("tok-1"));
        PaymentIntent charged = PaymentIntent.create(1L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS,
                10_000L, "IDR", "tok-1");
        charged.attachGatewayRef("gw-1", Map.of("payload", "qr://old")); // 已下单
        when(paymentIntentService.findByToken("tok-1")).thenReturn(Optional.of(charged));

        TopupResponse resp = service().create(1L, new CreateTopupRequest("10k", "QRIS"), "k");

        assertThat(resp.payload()).isEqualTo("qr://old");
        verify(gateway, never()).createCharge(any());
        verify(paymentIntentService, never()).attachCharge(anyString(), anyString(), any());
    }

    /** V85 / D-b：同档位未过期 PENDING 充值重开 → 复用同一 QR，不重复下单、不重调 GemPay。 */
    @Test
    void reuseSameTierPendingReturnsSameQrWithoutRecharge() {
        when(tierProvider.byId("10k")).thenReturn(TIER_10K);
        PaymentIntent pending = PaymentIntent.create(1L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS,
                10_000L, "IDR", "tok-reuse", java.time.Instant.now().plusSeconds(3600));
        pending.attachGatewayRef("gw-r", Map.of("payload", "qr://reuse"));
        when(paymentIntentService.findReusablePending(1L, PaymentPurpose.PAWCOIN_TOPUP, PayChannel.QRIS, 10_000L))
                .thenReturn(Optional.of(pending));

        TopupResponse resp = service().create(1L, new CreateTopupRequest("10k", "QRIS"), "k-new");

        assertThat(resp.intentToken()).isEqualTo("tok-reuse");
        assertThat(resp.payload()).isEqualTo("qr://reuse");
        verify(gateway, never()).createCharge(any());
        verify(paymentIntentService, never())
                .createIntent(anyLong(), any(), any(), anyLong(), anyString(), anyString(), any());
        verify(paymentIntentService, never()).attachCharge(anyString(), anyString(), any());
    }

    @Test
    void optionsReturnsTiersAndPausedFlag() {
        when(tierProvider.tiers()).thenReturn(List.of(TIER_10K, TIER_100K));
        when(platformConfig.pawcoin()).thenReturn(pawcoinConfig);
        when(pawcoinConfig.isTopupPaused()).thenReturn(true);

        TopupOptions o = service().options();

        assertThat(o.paused()).isTrue();
        assertThat(o.tiers()).hasSize(2);
        assertThat(o.tiers().get(0).id()).isEqualTo("10k");
        assertThat(o.tiers().get(0).amount()).isEqualTo(10_000L);
        assertThat(o.tiers().get(0).coins()).isEqualTo(10_000L);
    }
}
