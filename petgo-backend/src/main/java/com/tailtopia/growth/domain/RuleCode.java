package com.tailtopia.growth.domain;

/**
 * 成长值规则代码（V1.4.0 ① FR-143，Story 1.1）。与 {@code growth_rules.code}、{@code growth_ledger.rule_code} 一一对应，
 * 名称沿用 PRD 原文。
 */
public enum RuleCode {
    DAILY_LOGIN,
    POST_PUBLISHED,
    COMMENT_POSTED,
    LIKE_GIVEN,
    PLACE_CHECKIN,
    LIKE_RECEIVED,
    COMMENT_RECEIVED,
    COLLECT_RECEIVED,
    /** 伪行：被动三项当天合计上限（{@code daily_cap}），不产生入账（架构 V-1）。 */
    PASSIVE_GROUP,
    MILESTONE_S,
    MILESTONE_M,
    MILESTONE_L,
    OPS_CAMPAIGN,
    GIFT_SENT,
    GIFT_RECEIVED,
    SPEND_DIGITAL,
    SPEND_SHOP,
    /** 系统代码：上线存量补算（Story 2.2），后台不可编辑。 */
    LAUNCH_BACKFILL,
    /** 系统代码：运营手动调整（Epic 4），后台不可编辑。 */
    ADMIN_ADJUST,
    /**
     * 新手毕业：7 项新手任务全部完成、账号级 Lulus Pemula 解锁时 +20（V1.5.0 onboarding-recall Story 2.2 · OR-8，后台可调）。
     * 加在 {@code ADMIN_ADJUST} 之后，不改既有顺序；不是里程碑规则（{@link #isMilestone} 为 false，不受「同 code 5 次」上限）。
     */
    NEWBIE_GRADUATED;

    /** 被动三项共用的每日额度 counter_key / cap_group 值。 */
    public static final String PASSIVE_CAP_GROUP = "PASSIVE";

    /** 同账号同一里程碑 code 最多入账次数（PRD AC15a）。 */
    public static final int MILESTONE_MAX_PER_CODE = 5;

    public boolean isMilestone() {
        return this == MILESTONE_S || this == MILESTONE_M || this == MILESTONE_L;
    }
}
