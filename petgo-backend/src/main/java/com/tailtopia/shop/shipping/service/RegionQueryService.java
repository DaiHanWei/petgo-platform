package com.tailtopia.shop.shipping.service;

import com.tailtopia.shop.shipping.domain.ShippingZone;
import com.tailtopia.shop.shipping.dto.RegionTree;
import com.tailtopia.shop.shipping.repository.ShippingZoneRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 把扁平的 {@code shipping_zones} 折成三级树（Story 2.4）。 */
@Service
public class RegionQueryService {

    private final ShippingZoneRepository zones;

    public RegionQueryService(ShippingZoneRepository zones) {
        this.zones = zones;
    }

    /**
     * 🔴 <b>含 active=false 的区域</b>：FR-99 允许用户存下超范围地址，
     * 只在下单时阻断。级联里藏掉 inactive 会让「先存着等开通」变得不可能。
     */
    @Transactional(readOnly = true)
    public RegionTree tree() {
        // 仓储已按 provinsi → kota → kecamatan 排序，LinkedHashMap 保序即可
        Map<String, Map<String, List<RegionTree.Kecamatan>>> grouped = new LinkedHashMap<>();
        for (ShippingZone z : zones.findAllByOrderByProvinsiAscKotaKabupatenAscKecamatanAsc()) {
            grouped.computeIfAbsent(z.getProvinsi(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(z.getKotaKabupaten(), k -> new ArrayList<>())
                    .add(new RegionTree.Kecamatan(z.getKecamatan(), z.isActive()));
        }
        List<RegionTree.Provinsi> out = new ArrayList<>();
        grouped.forEach((prov, kotas) -> {
            List<RegionTree.Kota> kotaList = new ArrayList<>();
            kotas.forEach((kota, kecs) -> kotaList.add(new RegionTree.Kota(kota, kecs)));
            out.add(new RegionTree.Provinsi(prov, kotaList));
        });
        return new RegionTree(out);
    }
}
