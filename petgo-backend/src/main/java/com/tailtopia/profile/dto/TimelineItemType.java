package com.tailtopia.profile.dto;

/**
 * 时间线条目的**五类视觉分类**（V1.1.2 Story 3.2 · FR-82 · AD-2）。
 *
 * <p>⚠️ <b>词表由 App 侧 Story 2.2 定义，本枚举必须同名同值采纳，不得另立取值</b>
 * （对应 {@code petgo_app/lib/features/profile/domain/timeline_item.dart} 的 {@code TimelineItemType}）。
 * 枚举名即线格式（Jackson 直出 name），因此改名等于破坏对外契约。
 *
 * <p><b>三条硬约束（AD-2）：</b>
 * <ol>
 *   <li>分类由**服务端按五步优先级判定并下发**，前端只按标识选样式、不自行推断；</li>
 *   <li>**必须查询时实时计算，严禁落库固化**——否则「问诊跳过存档 → 日后补存」会让早先落库的
 *       banner 不消失，同一件事在时间线出现两条（AC3，安全攸关）；</li>
 *   <li>判定依据是「**这一天有没有对应的健康记录条目**」，<b>不是</b>里程碑声明的触发方式字段
 *       ——保证展示结果与完成路径无关、恒定一致（AC4）。</li>
 * </ol>
 */
public enum TimelineItemType {

    /** 类① 普通快乐时刻：标准照片卡，无任何徽章。 */
    HAPPY_MOMENT,

    /** 类② 打卡关联型里程碑：照片卡 + 右上金色徽章角标（同一条内容的样式变化，**不额外生成条目**）。 */
    HAPPY_MOMENT_MILESTONE,

    /** 类③ 系统自动型里程碑：横向通栏 banner，按 S/M/L 配色。 */
    MILESTONE_BANNER,

    /** 类④ 健康 / 问诊类记录：胶囊 / 标签条（结构化健康记录 + 问诊存档共用）。 */
    HEALTH_RECORD,

    /** 类⑤ 身份证解锁：证件卡（首次生成）。 */
    ID_CARD_ISSUED
}
