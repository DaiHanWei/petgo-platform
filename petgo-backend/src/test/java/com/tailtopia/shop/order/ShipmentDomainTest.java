package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShipmentProvider;
import com.tailtopia.shop.order.domain.ShipmentStatus;
import com.tailtopia.shop.order.domain.Shipments;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：v1.4.2 shop Story 5.1 AC2.1 —— Shipment 领域方法（Biteship 运单生命周期）。 */
class ShipmentDomainTest {

    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    private static Shipment biteship() {
        return Shipment.biteship(1L, "jne", "jne", "reg", "bs_1", "TOKO-1", null, null, 10_000L, 0L, T0);
    }

    @Test
    @DisplayName("Biteship 运单初态 CREATED、无揽收时刻、用户侧 awaiting_pickup；carrier 存大写")
    void biteshipInitialState() {
        Shipment s = biteship();
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(s.getShippedAt()).isNull();
        assertThat(s.getUserStatusKey()).isEqualTo("awaiting_pickup");
        assertThat(s.getProvider()).isEqualTo(ShipmentProvider.BITESHIP);
        assertThat(s.getCarrier()).isEqualTo("JNE");
        assertThat(s.getTrackingNo()).isNull();
        assertThat(s.isActive()).isTrue();
    }

    @Test
    @DisplayName("CREATED → SHIPPED → DELIVERED；揽收时刻只写一次、送达幂等")
    void createdShippedDelivered() {
        Shipment s = biteship();
        Instant picked = T0.plusSeconds(3600);
        s.markPicked(picked);
        s.markPicked(picked.plusSeconds(60));
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        assertThat(s.getShippedAt()).isEqualTo(picked);
        Instant delivered = picked.plusSeconds(86_400);
        s.markDelivered(delivered);
        s.markDelivered(delivered.plusSeconds(10));
        s.markPicked(delivered.plusSeconds(20));
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(s.getDeliveredAt()).isEqualTo(delivered);
    }

    @Test
    @DisplayName("CREATED 直达 DELIVERED：shipped_at 同写送达时刻")
    void createdToDeliveredFillsShippedAt() {
        Shipment s = biteship();
        s.markDelivered(T0.plusSeconds(100));
        assertThat(s.getShippedAt()).isEqualTo(T0.plusSeconds(100));
    }

    @Test
    @DisplayName("markCancelled 仅 CREATED；已取消运单非活动、不能送达")
    void cancelOnlyCreated() {
        Shipment s = biteship();
        s.markCancelled(T0);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(s.isActive()).isFalse();
        assertThatThrownBy(() -> s.markDelivered(T0)).isInstanceOf(AppException.class);

        Shipment picked = biteship();
        picked.markPicked(T0);
        assertThatThrownBy(() -> picked.markCancelled(T0)).isInstanceOf(AppException.class);
        Shipment manual = Shipment.ship(1L, Carrier.JNE, "JP1", 0L);
        assertThatThrownBy(() -> manual.markCancelled(T0)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("supersede 后非活动；isActive = 未取代且未取消")
    void supersede() {
        Shipment s = biteship();
        s.supersede(T0);
        s.supersede(T0.plusSeconds(5));
        assertThat(s.getSupersededAt()).isEqualTo(T0);
        assertThat(s.isActive()).isFalse();
    }

    @Test
    @DisplayName("provider_status_at 只前进；userKey 为 null 时不改用户侧状态")
    void providerStatusOnlyForward() {
        Shipment s = biteship();
        assertThat(s.applyProviderStatus("picked", T0.plusSeconds(10), "picked")).isTrue();
        assertThat(s.applyProviderStatus("confirmed", T0, "awaiting_pickup")).isFalse();
        assertThat(s.getProviderStatus()).isEqualTo("picked");
        assertThat(s.getUserStatusKey()).isEqualTo("picked");
        assertThat(s.applyProviderStatus("courier_not_found", T0.plusSeconds(20), null)).isTrue();
        assertThat(s.getUserStatusKey()).isEqualTo("picked");
        assertThat(s.getProviderStatusAt()).isEqualTo(T0.plusSeconds(20));
    }

    @Test
    @DisplayName("carrierDisplayName：三家取品牌写法，未知码原样；trackingUrl：手工取官网、Biteship 取列（仅 https）")
    void displayNameAndUrl() {
        assertThat(Shipment.ship(1L, Carrier.SICEPAT, "SC1", 0L).carrierDisplayName()).isEqualTo("SiCepat");
        assertThat(Shipment.ship(1L, Carrier.SICEPAT, "SC1", 0L).trackingUrl())
                .isEqualTo(Carrier.SICEPAT.trackingUrl());
        Shipment jnt = Shipment.biteship(1L, "jnt", "jnt", "ez", "bs_2", "R", "W", null, 0L, 0L, T0);
        assertThat(jnt.carrierDisplayName()).isEqualTo("JNT");
        assertThat(jnt.trackingUrl()).isNull();
        jnt.updateTrackingUrl("http://insecure");
        assertThat(jnt.trackingUrl()).isNull();
        jnt.updateTrackingUrl("https://track.example/W");
        assertThat(jnt.trackingUrl()).isEqualTo("https://track.example/W");
        assertThat(Carrier.fromCode("anteraja")).contains(Carrier.ANTERAJA);
        assertThat(Carrier.fromCode("jnt")).isEmpty();
        assertThat(Carrier.fromCode(null)).isEmpty();
    }

    @Test
    @DisplayName("运单号非空才覆盖，同步写 tracking_no；toString 不含 PII")
    void waybill() {
        Shipment s = biteship();
        s.updateWaybill(" WB9 ");
        s.updateWaybill("  ");
        assertThat(s.getWaybillId()).isEqualTo("WB9");
        assertThat(s.getTrackingNo()).isEqualTo("WB9");
        assertThat(s.toString()).contains("JNE").contains("WB9");
    }

    @Test
    @DisplayName("全部活动运单送达：superseded / 取消的不计；无活动运单为 false")
    void allActiveDelivered() {
        Shipment old = biteship();
        ReflectionTestUtils.setField(old, "id", 1L);
        old.markPicked(T0);
        old.supersede(T0);
        Shipment cur = biteship();
        ReflectionTestUtils.setField(cur, "id", 2L);
        cur.markDelivered(T0.plusSeconds(50));
        assertThat(Shipments.allActiveDelivered(List.of(old, cur))).isTrue();
        assertThat(Shipments.lastActiveDeliveredAt(List.of(old, cur))).contains(T0.plusSeconds(50));
        assertThat(Shipments.allActiveDelivered(List.of(old))).isFalse();
        assertThat(Shipments.hasActiveBiteship(List.of(old))).isFalse();
        assertThat(Shipments.hasActiveBiteship(List.of(old, cur))).isTrue();
    }

    @Test
    @DisplayName("C-6：物流异常 RETURNED / LOST / DAMAGED 阻止自动送达；PICKUP_FAILED / STALLED 不阻止")
    void exceptionBlocks() {
        assertThat(LogisticsException.RETURNED.blocksAutoDelivery()).isTrue();
        assertThat(LogisticsException.LOST.blocksAutoDelivery()).isTrue();
        assertThat(LogisticsException.DAMAGED.blocksAutoDelivery()).isTrue();
        assertThat(LogisticsException.PICKUP_FAILED.blocksAutoDelivery()).isFalse();
        assertThat(LogisticsException.STALLED.blocksAutoDelivery()).isFalse();
    }
}
