package com.tailtopia.place.web;

import com.tailtopia.place.dto.PlaceListResponse;
import com.tailtopia.place.service.PlaceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
     * 场所列表（AC2）。未带坐标 → 按创建时间倒序。
     *
     * <p>⚠️ Story 1.2 会在这里加 {@code lat}/{@code lng} 两个可选参数走距离分支；本 story 的
     * 「按最新」是**默认路径而不是降级路径** —— 无定位权限是 PRD ② 明定的正常态。
     */
    @GetMapping
    public PlaceListResponse list() {
        return query.listRecent();
    }
}
