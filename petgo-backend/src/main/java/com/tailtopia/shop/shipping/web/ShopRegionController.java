package com.tailtopia.shop.shipping.web;

import com.tailtopia.shop.shipping.dto.RegionTree;
import com.tailtopia.shop.shipping.service.RegionQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行政区划三级树（Story 2.4）。供 App 地址表单做级联选择。
 *
 * <p>🔒 <b>对游客开放</b>：区划与是否可配送都不是敏感信息，
 * 且用户在注册前就该能看到「你们送不送我这儿」。
 */
@RestController
public class ShopRegionController {

    private final RegionQueryService regions;

    public ShopRegionController(RegionQueryService regions) {
        this.regions = regions;
    }

    @GetMapping("/api/v1/shop/regions")
    public RegionTree tree() {
        return regions.tree();
    }
}
