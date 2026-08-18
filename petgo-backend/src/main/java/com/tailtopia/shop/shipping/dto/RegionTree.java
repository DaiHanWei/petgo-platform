package com.tailtopia.shop.shipping.dto;

import java.util.List;

/**
 * 行政区划三级树（Story 2.4 的级联选择数据源）。
 *
 * <p>🔴 <b>数据源是 {@code shipping_zones}，含 active=false 的区域。</b>
 * 这样用户可以存下「平台已录入但当前不可送达」的地址（FR-99 允许保存超范围地址），
 * 而下单时仍会被 {@code ShippingQuoteService} 挡住。
 *
 * <p>⚠️ <b>已知局限</b>：平台<b>从未录入过</b>的区域用户选不到，也就存不了那里的地址。
 * 彻底解法是引入一份完整的印尼行政区划基础数据（与 Kecamatan→邮编 对照表是同一份依赖）。
 * 在拿到那份数据之前，运营录入服务范围时应<b>把计划开通的区域也录进来并置 inactive</b>。
 */
public record RegionTree(List<Provinsi> provinsi) {

    public record Provinsi(String name, List<Kota> kota) {
    }

    public record Kota(String name, List<Kecamatan> kecamatan) {
    }

    /** {@code serviceable} = 当前可配送（active）。false 表示已录入但暂不送达。 */
    public record Kecamatan(String name, boolean serviceable) {
    }
}
