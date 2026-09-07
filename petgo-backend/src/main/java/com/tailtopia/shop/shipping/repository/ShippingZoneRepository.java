package com.tailtopia.shop.shipping.repository;

import com.tailtopia.shop.shipping.domain.ShippingZone;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 服务范围与运费表仓储（Story 2.2）。 */
public interface ShippingZoneRepository extends JpaRepository<ShippingZone, Long> {

    /**
     * 🔴 <b>只取 active 的区域</b>：下架区域仍在表里（历史订单运费需可追溯），
     * 但对试算与下单而言等同于不可配送。
     */
    Optional<ShippingZone> findByKecamatanAndActiveTrue(String kecamatan);

    Optional<ShippingZone> findByKecamatan(String kecamatan);

    List<ShippingZone> findAllByOrderByProvinsiAscKotaKabupatenAscKecamatanAsc();
}
