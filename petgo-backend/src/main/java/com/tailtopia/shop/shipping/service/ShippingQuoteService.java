package com.tailtopia.shop.shipping.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.dto.ShippingQuote;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import com.tailtopia.shop.shipping.repository.ShippingZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运费试算（Story 2.3，FR-99）。
 *
 * <p>🔴 <b>配送方式不是入参</b> —— C-14 后只剩 Reguler 一档。留一个恒等于 REGULER 的参数
 * 只会让调用方误以为多档已支持。
 *
 * <p>🔴 <b>服务范围的校验时机是「试算/结算」，不是「保存地址」</b>（FR-99）：
 * 用户存下一个当前不可送达的地址是合法的（他可能下个月搬过去、或先存着等平台开通），
 * 挡在保存这一步既没必要又损失数据。<b>阻断发生在下单前的最后一刻。</b>
 */
@Service
public class ShippingQuoteService {

    private final ShippingZoneRepository zones;
    private final ShippingSettingsRepository settings;

    public ShippingQuoteService(ShippingZoneRepository zones, ShippingSettingsRepository settings) {
        this.zones = zones;
        this.settings = settings;
    }

    /**
     * 按 Kecamatan 试算运费。
     *
     * @param kecamatan   收货地址的 Kecamatan
     * @param goodsSubtotal 商品小计（最小币种单位），用于判定是否达免运门槛
     * @throws AppException 该 Kecamatan 不在服务范围内 —— 🔴 <b>明确的领域错误，不是笼统报错</b>：
     *     用户需要知道「是这个地址送不到」而不是「出错了」，否则只会反复重试同一个地址。
     */
    @Transactional(readOnly = true)
    public ShippingQuote quote(String kecamatan, long goodsSubtotal) {
        if (kecamatan == null || kecamatan.isBlank()) {
            throw AppException.validation("请选择收货地址");
        }
        var zone = zones.findByKecamatanAndActiveTrue(kecamatan.trim())
                .orElseThrow(() -> AppException.validation(
                        "暂不配送至 %s，请更换收货地址".formatted(kecamatan.trim())));

        long threshold = threshold();
        // threshold 为 0 表示不做免运（不是「0 元即免运」）
        boolean free = threshold > 0 && goodsSubtotal >= threshold;
        return ShippingQuote.of(zone.getKecamatan(), zone.getFee(), free);
    }

    /** 该 Kecamatan 当前是否可配送。供 UI 提示用；下单阻断仍走 {@link #quote}。 */
    @Transactional(readOnly = true)
    public boolean isServiceable(String kecamatan) {
        return kecamatan != null
                && zones.findByKecamatanAndActiveTrue(kecamatan.trim()).isPresent();
    }

    @Transactional(readOnly = true)
    public long threshold() {
        return settings.findById(ShippingSettings.SINGLETON_ID)
                .map(ShippingSettings::getFreeShippingThreshold)
                .orElse(0L);
    }
}
