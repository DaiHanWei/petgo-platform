package com.tailtopia.place.service;

import java.math.BigDecimal;

/**
 * 场所所在城市（2026-09-18 场所表对齐 · 决策 D2）。
 *
 * <p>后台的 {@code places.city} 是必填（D-39，后台按城市筛选），而 App 标记场所时没有城市字段 ——
 * 规定不接第三方地理服务、不反查地址。本版只在雅加达运营，所以唯一实现返回配置的默认城市。
 *
 * <p>🔴 <b>为多城市预留</b>：签名带上坐标，是给将来「按坐标区域判城市」或「App 传入城市」留的口子。
 * 开新城市时只换这里的实现，数据库与调用方都不用动。<b>不要</b>在实体、DTO 或 SQL 里写死城市名。
 */
public interface PlaceCityResolver {

    /** 给一个新标记的场所定城市。返回值非空、≤60 字（{@code places.city} 列宽）。 */
    String resolve(BigDecimal lat, BigDecimal lng);

    /** 后台录入表单的默认值（与 App 标记同一来源，保证两边写法一致）。 */
    String defaultCity();
}
