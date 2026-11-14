package com.tailtopia.profile.health;

/**
 * 表单提示（V1.5.0 Story 1.2，架构增量 §6.1 preview {@code hints}）。<b>声明顺序 = 输出顺序</b>（固定）。
 * App 按 code 本地化（PRD FR-159 / FR-160 / FR-171 文案），不复制判定规则。
 */
public enum PreviewHint {
    VACCINE_PREGNANT,
    RABIES_YEARLY,
    DEWORM_UNDER_8_WEEKS,
    DEWORM_PREGNANT,
    PERMETHRIN,
    HEARTWORM_TEST,
    HEAT_NEUTERED
}
