package com.tailtopia.admin.usertag.dto;

import java.time.Instant;

/**
 * 用户标签分配记录的一行（Story 11.3）。
 *
 * @param visible 🔴 该条**当前是否会真的展示出来**。用户端同时只展示 3 个，
 *                超出的记录保留在库但不展示 —— 不把这一列摆出来，运营会以为
 *                「分配了就一定看得见」。
 *                ⚠️ 该值由 {@code UserTagQueryService.findVisibleTags}（App 侧那份权威实现）
 *                算出，后台**不自行排序**。
 * @param deletedUser 该账号已注销（bug 20260828）。注销账号的标签**永远不展示** ——
 *                单看 {@code visible=false} 运营会以为是「被前 3 个顶掉了」，
 *                于是去撤别的标签、或怀疑图标坏了。这一列回答的是**为什么**。
 */
public record UserAssignmentRow(long id, long userId, long tagId, String tagCode, String tagName,
        Instant startsAt, Instant endsAt, boolean visible, boolean deletedUser) {

    public boolean permanent() {
        return endsAt == null;
    }
}
