package com.tailtopia.admin.usertag.dto;

import java.time.Instant;

/**
 * 用户标签分配记录的一行（Story 11.3）。
 *
 * @param visible 🔴 该条**当前是否会真的展示出来**。
 *                ⚠️ 该值由 {@code UserTagQueryService.findVisibleTags}（App 侧那份权威实现）
 *                算出，后台**不自行排序**。
 * @param hiddenReason 不展示时的**原因**（bug 20260828）；展示中为 {@code null}。
 *                取值见 {@link #REASON_DELETED_USER} 等常量，是 i18n 键的后缀。
 *
 *                <p>🔴 只给一个「不展示」是不够的 —— 运营两次都栽在这里：
 *                一次以为是图标坏了（实际是账号已注销），一次以为标签没生效
 *                （可能是开始时间填成了未来）。这四种原因的**处置动作完全不同**：
 *                注销要撤掉这条、未开始要等或改时间、已结束要重新分配、
 *                被顶掉要撤掉别的标签。合并成一个「不展示」等于什么都没说。
 */
public record UserAssignmentRow(long id, long userId, long tagId, String tagCode, String tagName,
        Instant startsAt, Instant endsAt, boolean visible, String hiddenReason) {

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

    /** 已注销（模板里单独高亮这一种：它是唯一「撤掉才对」的）。 */
    public boolean deletedUser() {
        return REASON_DELETED_USER.equals(hiddenReason);
    }
}
