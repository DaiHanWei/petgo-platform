package com.tailtopia.shop.address.service;

import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.address.dto.ShippingAddressView;
import com.tailtopia.shop.logistics.QuoteFailedException;
import com.tailtopia.shop.logistics.QuoteResult;
import com.tailtopia.shop.logistics.ShippingQuoteService;
import com.tailtopia.shop.shipping.repository.ShippingZoneRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 地址簿「可配送」标注（v1.4.2 shop Story 3.1 AC6，D-9 第 4 点 / FR-133.11）。
 *
 * <p>判定：Kecamatan 未开通 → {@code false}（不询价）；已开通 → 1.1 标准探测包裹按 {@code kode_pos} 询价
 * （{@link ShippingQuoteService#probe}，落 {@code shipping_quotes}、复用 24 小时），≥ 1 家 → {@code true}，
 * 0 家 → {@code false}；询价失败 → {@code null}（App 不标注）。
 *
 * <p>🔴 <b>本类不加事务</b>：先在只读事务里取地址列表并关闭事务，再逐个邮编询价（P-7）；
 * 同一请求内相同邮编只询价一次。🔒 不打含地址 / 邮编的日志。
 */
@Service
public class AddressServiceabilityService {

    private final ShippingAddressService addresses;
    private final ShippingZoneRepository zones;
    private final ShippingQuoteService quotes;

    public AddressServiceabilityService(ShippingAddressService addresses,
            ShippingZoneRepository zones, ShippingQuoteService quotes) {
        this.addresses = addresses;
        this.zones = zones;
        this.quotes = quotes;
    }

    public List<ShippingAddressView> listWithServiceability(long userId) {
        List<ShippingAddress> list = addresses.list(userId);   // 只读事务，返回即关闭
        Map<String, Boolean> zoneOpen = new HashMap<>();
        Map<String, Boolean> byPostal = new HashMap<>();
        List<ShippingAddressView> out = new ArrayList<>(list.size());
        for (ShippingAddress a : list) {
            out.add(ShippingAddressView.of(a, serviceable(a, userId, zoneOpen, byPostal)));
        }
        return out;
    }

    private Boolean serviceable(ShippingAddress a, long userId, Map<String, Boolean> zoneOpen,
            Map<String, Boolean> byPostal) {
        String kec = a.getKecamatan() == null ? null : a.getKecamatan().trim();
        boolean open = kec != null && zoneOpen.computeIfAbsent(kec,
                k -> zones.findByKecamatanAndActiveTrue(k).isPresent());
        if (!open) {
            return false;
        }
        String postal = a.getKodePos() == null ? "" : a.getKodePos().trim();
        if (byPostal.containsKey(postal)) {
            return byPostal.get(postal);   // 可能是 null（本请求内已失败过，不再重试）
        }
        Boolean result;
        try {
            QuoteResult q = quotes.probe(postal, userId);
            result = q.serviceable() && !q.rates().isEmpty();
        } catch (QuoteFailedException e) {
            result = null;
        }
        byPostal.put(postal, result);
        return result;
    }
}
