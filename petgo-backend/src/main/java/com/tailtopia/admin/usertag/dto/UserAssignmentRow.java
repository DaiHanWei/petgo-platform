package com.tailtopia.admin.usertag.dto;

import java.time.Instant;

/**
 * 用户标签分配记录的一行（Story 11.3；V1.3.0 Story 8.2 补齐抽屉页签二要的列）。
 *
 * @param visible 🔴 该条**当前是否会真的展示出来**。
 *                ⚠️ 该值由 {@code UserTagQueryService.findVisibleTags}（App 侧那份权威实现）
 *                算出，后台**不自行排序**。
 *                ⚠️ 与 {@link #active} 不是一回事：一条**生效中**的分配完全可能因为被更晚的
 *                三个标签顶掉而**不展示**。状态列要同时说清这两件事。
 * @param hiddenReason 不展示时的**原因**（bug 20260828）；展示中为 {@code null}。
 *                取值见 {@link #REASON_DELETED_USER} 等常量，是 i18n 键的后缀。
 *
 *                <p>🔴 只给一个「不展示」是不够的 —— 运营两次都栽在这里：
 *                一次以为是图标坏了（实际是账号已注销），一次以为标签没生效
 *                （可能是开始时间填成了未来）。这四种原因的**处置动作完全不同**：
 *                注销要撤掉这条、未开始要等或改时间、已结束要重新分配、
 *                被顶掉要撤掉别的标签。合并成一个「不展示」等于什么都没说。
 */
public record UserAssignmentRow(long id, long userId,
        /** 用户显示名（V1.3.0 Story 8.2 · AC3）：抽屉里的「用户」列光有 id 认不出人。 */
        String userName,
        long tagId, String tagCode, String tagName,
        Instant startsAt, Instant endsAt,
        /**
         * 分配时刻（V1.3.0 Story 8.2 · AC3）。
         *
         * <p>⚠️ 与 {@code startsAt} <b>不是一回事</b>：本页支持「先排期、后生效」，
         * 开始时间可以填未来。两列并排才看得出「这条是什么时候排的、什么时候才生效」。
         */
        Instant createdAt,
        /**
         * 操作人显示名（V1.3.0 Story 8.2 · AC3）。
         *
         * <p>⚠️ 分配表上**没有操作人列** —— 取自审计里那条 {@code USER_TAG_ASSIGN}
         * （target = 本条分配 id，8.2 起逐条记）。8.2 之前经批量分配落库的记录只有一条
         * 汇总审计（target = 标签 id），反查不到，界面显示「—」。
         */
        String actorName,
        /**
         * 当前是否在生效窗口内（{@code [startsAt, endsAt)}，{@code endsAt} 空 = 永久）。
         *
         * <p>⚠️ 在服务层按同一个 {@code now} 算好再传进来，模板不做时间判断 ——
         * 与 7.4 内容标签同构；模板里各算各的会出现同屏两条记录用了两个「现在」。
         */
        boolean active,
        /**
         * 尚未开始（{@code startsAt > now}）。
         *
         * <p>🔴 状态必须是**三态**，不能只有「生效中 / 已到期」两档：本页明确支持先排期后生效。
         * 把一条排在下周的分配写成「已到期」，运营的合理解读是「排期作废了」，接着会再分配一次。
         */
        boolean pending,
        boolean visible, String hiddenReason) {

    /** 账号已注销 —— 匿名化之后不再挂身份标识，**永不展示**，撤掉这条即可。 */
    public static final String REASON_DELETED_USER = "deletedUser";
    /** 还没到生效时间。⚠️ 时间按 WIB 解释，运营按自己所在时区填就会填成未来。 */
    public static final String REASON_NOT_STARTED = "notStarted";
    /** 已过结束时间。 */
    public static final String REASON_ENDED = "ended";
    /** 生效中，但被更晚分配的标签挤出了展示上限。 */
    public static final String REASON_OVER_CAP = "overCap";

    public boolean permanent() {
        return endsAt == null;
    }

    /** 已到期：既非生效中、也不是还没开始。 */
    public boolean expired() {
        return !active && !pending;
    }

    /** 已注销（模板里单独高亮这一种：它是唯一「撤掉才对」的）。 */
    public boolean deletedUser() {
        return REASON_DELETED_USER.equals(hiddenReason);
    }
}
