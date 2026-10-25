/**
 * 商家平台（tailtopia-merchant）对接（V1.4.1）。契约以商家仓库 {@code docs/integration/tailtopia-contract.md} 为准，
 * 改动须先改那份文档。
 *
 * <ul>
 *   <li>{@code store}：只读 {@code merchant_stores} 的四个契约列（{@code id / invite_code / status / public_token}，AD-21）。
 *       那张表归商家平台，本仓库<b>不映射 JPA 实体</b>（{@code ddl-auto=validate} 会让没跑商家迁移的环境启动即失败），只走 JDBC。</li>
 *   <li>{@code binding}：App 用户填邀请码绑定门店（FR-7 / FR-40 / FR-41），下单据此写 {@code shop_orders.referrer_merchant_id}。</li>
 *   <li>{@code po}：门店进货单与内部接口 {@code /internal/merchant/**}（契约第 5 节）。</li>
 * </ul>
 */
package com.tailtopia.merchant;
