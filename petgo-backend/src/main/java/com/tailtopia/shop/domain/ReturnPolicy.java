package com.tailtopia.shop.domain;

/**
 * 退货规则标识（FR-94 ⑫ / FR-104）。
 *
 * <p>🔴 <b>三值不含「换」</b>——换货已砍出本版本（{@code decision-log} C-13）：两份 PRD 中换货流程
 * 零实现（无换货状态、无质检后补发、无补发发货、无换货出入库配对），运营一旦标出「可退可换」，
 * 用户端会据此展示<b>平台无法兑现的承诺</b>。用户走「退货 → 重新下单」。
 *
 * <p>落库 varchar + CHECK，UPPER_SNAKE。SKU 级可空 = 继承商品级（FR-94A）。
 */
public enum ReturnPolicy {
    /** 可退 */
    RETURNABLE,
    /** 开封不退——宠物食品与保健品的安全侧默认值；须在商品详情页、结算页、退货申请页三处明示（FR-104） */
    NO_RETURN_AFTER_OPEN,
    /** 不可退——不提供退货入口 */
    NON_RETURNABLE
}
