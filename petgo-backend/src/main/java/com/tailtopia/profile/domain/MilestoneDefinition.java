package com.tailtopia.profile.domain;

/**
 * 里程碑清单单项定义（FR-42 后端固定常量元素，见 {@link MilestoneCatalog}）。
 *
 * @param code        目录码（C-S1 / D-M3 / G-L1…），稳定外露标识、非顺序 id
 * @param level       级别 S/M/L
 * @param trigger     触发方式
 * @param sortOrder   清单内全局展示次序（前端按 level 分区后再按 sortOrder 升序）
 * @param titleZh     中文标题（V1 文案常量；i18n 是既有系统级缺口，全模块统一改时收口）
 * @param titleId     印尼语标题（V1.1.6 Story 1.2）。<b>H5 分享页专用</b> —— 该页是服务端渲染的
 *                    Thymeleaf，拿不到 App 那套客户端本地化，故后端必须自带一份。
 *                    ⚠️ 取值<b>逐条来自</b> App 的 {@code milestone_titles.dart}（{@code kMilestoneTitles} 的
 *                    {@code id} 字段），<b>不是另行翻译的</b>；两处走散会让同一里程碑在 App 与分享页显示两套词。
 *                    {@code MilestoneCatalogI18nTest} 会逐条比对，改一处漏一处即红。
 */
public record MilestoneDefinition(
        String code,
        MilestoneLevel level,
        MilestoneTriggerType trigger,
        int sortOrder,
        String titleZh,
        String titleId) {
}
