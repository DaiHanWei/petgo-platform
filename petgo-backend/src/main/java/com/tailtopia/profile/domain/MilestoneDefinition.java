package com.tailtopia.profile.domain;

/**
 * 里程碑清单单项定义（FR-42 后端固定常量元素，见 {@link MilestoneCatalog}）。
 *
 * @param code        目录码（C-S1 / D-M3 / G-L1…），稳定外露标识、非顺序 id
 * @param level       级别 S/M/L
 * @param trigger     触发方式
 * @param sortOrder   清单内全局展示次序（前端按 level 分区后再按 sortOrder 升序）
 * @param titleZh     中文标题（V1 文案常量；i18n 是既有系统级缺口，全模块统一改时收口）
 */
public record MilestoneDefinition(
        String code,
        MilestoneLevel level,
        MilestoneTriggerType trigger,
        int sortOrder,
        String titleZh) {
}
