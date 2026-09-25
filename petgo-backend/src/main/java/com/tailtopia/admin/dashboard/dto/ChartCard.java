package com.tailtopia.admin.dashboard.dto;

import java.util.List;

/**
 * 看板一张图表卡（V1.3.0 Story 3.4）。
 *
 * @param id          卡 id：users / pets / content / engagement / payment（也是 fragment 的 data-kind 与 i18n key 后缀）
 * @param metricKeys  卡内指标 key（口径表顺序）
 * @param scopeTabs   是否有「真实用户 / 含种子」tab（帖子类双口径，D-29）
 * @param payTabs     是否有「现金到账 / 含 PawCoin 消费」tab（付费卡）
 * @param series      全部序列（双口径卡含 ALL + REAL；付费卡含两口径四条）
 * @param missingDays 范围内没有任何物化行的天数（图上为断点；卡底提示）
 * @param empty       整卡无数据（每一天都没有物化行）
 * @param json        内嵌 {@code <script type="application/json">} 的 {@code {"labels":[…],"series":[…]}}
 */
public record ChartCard(String id, List<String> metricKeys, boolean scopeTabs, boolean payTabs, List<ChartSeries> series,
        int missingDays, boolean empty, String json) {
}
