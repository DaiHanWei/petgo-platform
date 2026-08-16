package com.tailtopia.admin.moderation.dto;

/**
 * 统一工单的三态（Story 3.1 AC9）。四张源表各有各的状态机，读时映射到这三档。
 *
 * <p>⚠️ 第三档在**界面上叫「无需处置」**（{@code Tidak Perlu Tindakan} / No Action Needed，C-103），
 * <b>不叫「已驳回」</b>：这一档里混着「审核通过、本来就没问题」的记录，管它叫「驳回」会让运营
 * 误以为自己否掉了什么。⚠️ 但**举报侧的「驳回」动作按钮仍然叫驳回**——那个动作是准确的，别一起改。
 */
public enum TicketStatusBucket {

    /** 待处理。账号标识字段两表的共同锚点是 {@code MANUAL_PENDING}。 */
    PENDING,

    /** 已处理（内容下架 / 账号处置 / 标识字段判违规）。 */
    RESOLVED,

    /** 无需处置（举报被驳回 / 标识字段审核通过）。 */
    NO_ACTION
}
