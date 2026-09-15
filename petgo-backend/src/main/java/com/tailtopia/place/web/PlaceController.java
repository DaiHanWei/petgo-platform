package com.tailtopia.place.web;

import com.tailtopia.place.domain.GeoBox;
import com.tailtopia.place.dto.PlaceListResponse;
import com.tailtopia.place.service.PlaceQueryService;
import com.tailtopia.shared.error.AppException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场所只读端点（V1.3.0 batch-b1 Story 1.1 · FR-112.2）。
 *
 * <p>🔴 <b>GET 对游客放行</b>（见 {@code SecurityConfig}）：场所列表是「这个功能里已经攒了些
 * 什么地方」的展示面，用登录墙拦它没有任何意义 —— 同 Toko 商品列表的既定取舍。
 * App 侧对应地<b>不把 {@code /places} 放进 {@code _controlledLocations}</b>（Story 1.1 Dev Notes）。
 * 写端点（标记场所，Story 1.3）仍需 JWT。
 *
 * <p>🔴 <b>路径与返回体都不出现自增 id</b>：对外寻址只用不可枚举 token（AD-1 Rule 3）。
 *
 * <p><b>只读，而且没有编辑端点</b>：用户不可修改场所（2026-09-15 拍板）——
 * 服务端<b>不提供</b>任何 PATCH/PUT，纠错走后台 AB-17A。要加编辑接口，先回决策日志改口径。
 */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    private final PlaceQueryService query;

    public PlaceController(PlaceQueryService query) {
        this.query = query;
    }

    /**
     * 场所列表（Story 1.1 AC2 / Story 1.2 AC1）。
     *
     * <p>带 {@code lat}+{@code lng} → 按直线距离升序；不带 → 按创建时间倒序。
     * 「按最新」是**默认路径而不是降级路径** —— 无定位权限是 PRD ② 明定的正常态。
     *
     * <p>🔴 <b>坐标只能同时给或同时不给</b>，且必须落在合法区间（纬度 ±90 / 经度 ±180）：
     * 违反即 <b>422</b>，不静默忽略。静默忽略会让客户端拿到「按最新」的列表却以为是按距离排的 ——
     * 用户看到的是「最近的店在 20 公里外」，而没有任何地方能看出坐标其实没送到
     * （同 {@code ShopProductController} 对非法品类的处理）。
     *
     * <p>🛡 <b>坐标绝不进日志</b>（NFR-4/NFR-5）：这里不打任何带 lat/lng 的日志，
     * 校验失败的 ProblemDetail 里也不回显坐标值。
     */
    @GetMapping
    public PlaceListResponse list(@RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        if ((lat == null) != (lng == null)) {
            throw AppException.validation("经纬度必须同时提供");
        }
        if (lat != null && !GeoBox.isValidCoordinate(lat, lng)) {
            throw AppException.validation("坐标超出合法范围");
        }
        return query.list(lat, lng);
    }
}
